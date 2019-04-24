package osmesa.analytics

import geotrellis.proj4.{LatLng, WebMercator}
import geotrellis.raster._
import geotrellis.raster.resample.Sum
import geotrellis.spark.SpatialKey
import geotrellis.spark.tiling.ZoomedLayoutScheme
import geotrellis.vector.Geometry
import org.apache.spark.internal.Logging
import org.apache.spark.sql.Dataset
import osmesa.analytics.vectorgrid._
import osmesa.common.raster.MutableSparseIntTile

import scala.collection.mutable.ArrayBuffer

object Implicits extends Logging {
  implicit class ExtendedCoordinatesWithKey(val coords: Dataset[CoordinatesWithKey]) {
    import coords.sparkSession.implicits._

    def tile(baseZoom: Int)(
        implicit layoutScheme: ZoomedLayoutScheme): Dataset[GeometryTileWithKey] = {
      val layout = layoutScheme.levelForZoom(baseZoom).layout
      val xs = 0 until layout.layoutCols
      val ys = 0 until layout.layoutRows

      coords
        .flatMap { point =>
          val geom = Geometry(point.geom)

          Option(geom).map(_.reproject(LatLng, WebMercator)) match {
            case Some(g) if g.isValid =>
              layout.mapTransform
                .keysForGeometry(g)
                .flatMap { sk =>
                  g.intersection(sk.extent(layout)).toGeometry match {
                    case Some(clipped) if clipped.isValid =>
                      if (xs.contains(sk.col) && ys.contains(sk.row)) {
                        Seq(GeometryTileWithKey(point.key, baseZoom, sk, clipped.jtsGeom))
                      } else {
                        // TODO figure out why this happens (0/0/-5)
                        // it might be features extending outside Web Mercator bounds, in which case those features
                        // belong in valid tiles (but need to have their coordinates adjusted to account for that)
                        log.warn(s"Out of range: ${sk.col}, ${sk.row}, ${clipped}")
                        Seq.empty[GeometryTileWithKey]
                      }
                    case _ =>
                      Seq.empty[GeometryTileWithKey]
                  }
                }
            case _ => Seq.empty[GeometryTileWithKey]
          }
        }
    }
  }

  implicit class ExtendedRasterTileWithKey(val rasterTiles: Dataset[RasterTileWithKey]) {
    import rasterTiles.sparkSession.implicits._

    def pyramid(baseZoom: Int)(implicit layoutScheme: ZoomedLayoutScheme,
                               cols: Int): Dataset[RasterTileWithKey] = {
      // Δz between levels of the pyramid; point at which 1 tile becomes 1 pixel
      val dz = (math.log(cols) / math.log(2)).toInt

      (baseZoom to 0 by -dz)
        .foldLeft(rasterTiles)((acc, z) => acc.downsample(dz, z).merge)
    }

    /**
      * Merge tiles containing raster subsets.
      *
      * @return Merged tiles.
      */
    def merge(implicit layoutScheme: ZoomedLayoutScheme, cells: Int): Dataset[RasterTileWithKey] = {
      import rasterTiles.sparkSession.implicits._

      rasterTiles
        .groupByKey(tile => (tile.key, tile.zoom, tile.sk))
        .mapGroups {
          case ((k, z, sk), tiles: Iterator[RasterTileWithKey]) =>
            val mergedExtent = sk.extent(layoutScheme.levelForZoom(z).layout)

            val merged = tiles.foldLeft(MutableSparseIntTile(cells, cells): Tile)((acc, tile) => {
              if (tile.raster.extent == mergedExtent) {
                // full resolution raster covering the target extent; no need to merge
                tile.raster.tile
              } else {
                acc.merge(mergedExtent, tile.raster.extent, tile.raster.tile, Sum)
              }
            })

            RasterTileWithKey(k, z, sk, (merged, mergedExtent))
        }
    }

    /**
      * Create downsampled versions of input tiles. Will stop when downsampled dimensions drop below 1x1.
      *
      * @param steps Number of steps to pyramid.
      * @param baseZoom Zoom level for the base of the pyramid.
      * @return Base + downsampled tiles.
      */
    def downsample(steps: Int, baseZoom: Int)(implicit cells: Int): Dataset[RasterTileWithKey] =
      rasterTiles
        .flatMap(tile =>
          if (tile.zoom == baseZoom) {
            val raster = tile.raster
            var parent = raster.tile

            (tile.zoom to math.max(0, tile.zoom - steps) by -1).iterator.flatMap {
              zoom =>
                if (zoom == tile.zoom) {
                  Seq(tile)
                } else {
                  val dz = tile.zoom - zoom
                  val factor = math.pow(2, dz).intValue
                  val newCells = cells / factor

                  if (parent.cols > newCells && newCells > 0) {
                    // only resample if the raster is getting smaller
                    parent = parent.resample(newCells, newCells, Sum)

                    Seq(
                      RasterTileWithKey(tile.key,
                                        zoom,
                                        SpatialKey(tile.sk.col / factor, tile.sk.row / factor),
                                        (parent, raster.extent)))
                  } else {
                    Seq.empty[RasterTileWithKey]
                  }
                }
            }
          } else {
            Seq(tile)
        })
  }

  implicit class ExtendedRasterTileWithKeyAndSequence(
      val rasterTiles: Dataset[RasterTileWithKeyAndSequence]) {
    import rasterTiles.sparkSession.implicits._

    def pyramid(baseZoom: Int)(implicit layoutScheme: ZoomedLayoutScheme,
                               cells: Int): Dataset[RasterTileWithKeyAndSequence] = {
      // Δz between levels of the pyramid; point at which 1 tile becomes 1 pixel
      val dz = (math.log(cells) / math.log(2)).toInt

      (baseZoom to 0 by -dz)
        .foldLeft(rasterTiles)((acc, z) => acc.downsample(dz, z).merge)
    }

    /**
      * Create downsampled versions of input tiles. Will stop when downsampled dimensions drop below 1x1.
      *
      * @param steps Number of steps to pyramid.
      * @param baseZoom Zoom level for the base of the pyramid.
      * @return Base + downsampled tiles.
      */
    def downsample(steps: Int, baseZoom: Int)(
        implicit cells: Int): Dataset[RasterTileWithKeyAndSequence] =
      rasterTiles
        .flatMap(tile =>
          if (tile.zoom == baseZoom) {
            val tiles = ArrayBuffer(tile)

            var parent = tile.raster.tile

            for (zoom <- tile.zoom - 1 to math.max(0, tile.zoom - steps) by -1) {
              val dz = tile.zoom - zoom
              val factor = math.pow(2, dz).intValue
              val newCells = cells / factor

              if (parent.cols > newCells && newCells > 0) {
                // only resample if the raster is getting smaller
                parent = parent.resample(newCells, newCells, Sum)

                tiles.append(
                  RasterTileWithKeyAndSequence(tile.sequence,
                                               tile.key,
                                               zoom,
                                               SpatialKey(tile.sk.col / factor,
                                                          tile.sk.row / factor),
                                               (parent, tile.raster.extent)))
              }
            }

            tiles
          } else {
            Seq(tile)
        })

    /**
      * Merge tiles containing raster subsets.
      *
      * @return Merged tiles.
      */
    def merge(implicit layoutScheme: ZoomedLayoutScheme,
              cells: Int): Dataset[RasterTileWithKeyAndSequence] = {
      import rasterTiles.sparkSession.implicits._

      rasterTiles
        .groupByKey(tile => (tile.sequence, tile.key, tile.zoom, tile.sk))
        .mapGroups {
          case ((sequence, k, z, sk), tiles: Iterator[RasterTileWithKeyAndSequence]) =>
            val mergedExtent = sk.extent(layoutScheme.levelForZoom(z).layout)

            val merged = tiles.foldLeft(MutableSparseIntTile(cells, cells): Tile)((acc, tile) => {
              if (tile.raster.extent == mergedExtent) {
                // full resolution raster covering the target extent; no need to merge
                tile.raster.tile
              } else {
                acc.merge(mergedExtent, tile.raster.extent, tile.raster.tile, Sum)
              }
            })

            RasterTileWithKeyAndSequence(sequence, k, z, sk, (merged, mergedExtent))
        }
    }
  }

  implicit class ExtendedGeometryTileWithKey(val geometryTiles: Dataset[GeometryTileWithKey]) {
    def rasterize(cells: Int)(
        implicit layoutScheme: ZoomedLayoutScheme): Dataset[RasterTileWithKey] = {
      import geometryTiles.sparkSession.implicits._

      geometryTiles
        .groupByKey(tile => (tile.key, tile.zoom, tile.sk))
        .mapGroups {
          case ((k, z, sk), tiles) =>
            val tileExtent = sk.extent(layoutScheme.levelForZoom(z).layout)
            val tile = MutableSparseIntTile(cells, cells)
            val rasterExtent = RasterExtent(tileExtent, tile.cols, tile.rows)
            val geoms = tiles.map(_.geom)

            geoms.foreach(g =>
              Geometry(g).foreach(rasterExtent) { (c, r) =>
                tile.get(c, r) match {
                  case v if isData(v) => tile.set(c, r, v + 1)
                  case _              => tile.set(c, r, 1)
                }
            })

            RasterTileWithKey(k, z, sk, (tile, tileExtent))
        }
    }
  }

  implicit class ExtendedGeometryTileWithKeyAndSequence(
      val geometryTiles: Dataset[GeometryTileWithKeyAndSequence]) {
    def rasterize(cells: Int)(
        implicit layoutScheme: ZoomedLayoutScheme): Dataset[RasterTileWithKeyAndSequence] = {
      import geometryTiles.sparkSession.implicits._

      geometryTiles
        .groupByKey(tile => (tile.sequence, tile.key, tile.zoom, tile.sk))
        .mapGroups {
          case ((sequence, k, z, sk), tiles) =>
            val tileExtent = sk.extent(layoutScheme.levelForZoom(z).layout)
            val tile = MutableSparseIntTile(cells, cells)
            val rasterExtent = RasterExtent(tileExtent, tile.cols, tile.rows)
            val geoms = tiles.map(_.geom)

            geoms.foreach(g =>
              Geometry(g).foreach(rasterExtent) { (c, r) =>
                tile.get(c, r) match {
                  case v if isData(v) => tile.set(c, r, v + 1)
                  case _              => tile.set(c, r, 1)
                }
            })

            RasterTileWithKeyAndSequence(sequence, k, z, sk, (tile, tileExtent))
        }
    }
  }

  implicit class ExtendedCoordinatesWithKeyAndSequence(
      val coords: Dataset[CoordinatesWithKeyAndSequence]) {
    import coords.sparkSession.implicits._

    def tile(baseZoom: Int)(
        implicit layoutScheme: ZoomedLayoutScheme): Dataset[GeometryTileWithKeyAndSequence] = {
      val layout = layoutScheme.levelForZoom(baseZoom).layout

      coords
        .flatMap { point =>
          val geom = point.geom

          Option(geom).map(Geometry(_)).map(_.reproject(LatLng, WebMercator)) match {
            case Some(g) if g.isValid =>
              layout.mapTransform
                .keysForGeometry(g)
                .flatMap { sk =>
                  g.intersection(sk.extent(layout)).toGeometry match {
                    case Some(clipped) if clipped.isValid =>
                      Seq(
                        GeometryTileWithKeyAndSequence(point.sequence,
                                                       point.key,
                                                       baseZoom,
                                                       sk,
                                                       clipped.jtsGeom))
                    case _ => Seq.empty[GeometryTileWithKeyAndSequence]
                  }
                }
            case _ => Seq.empty[GeometryTileWithKeyAndSequence]
          }
        }
    }
  }
}