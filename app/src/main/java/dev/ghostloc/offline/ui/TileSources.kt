package dev.ghostloc.offline.ui

import dev.ghostloc.offline.core.Prefs
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex

/**
 * Tile sources for the map tab. AMap servers are directly reachable from
 * mainland China (unlike OSM), but their tiles are drawn in GCJ-02, so picked
 * coordinates must be corrected back to WGS-84 before feeding the hook layer.
 */
object TileSources {

    data class Source(val id: String, val isGcj02: Boolean, val factory: () -> OnlineTileSourceBase)

    private fun amapSource(name: String, style: Int): OnlineTileSourceBase {
        return object : OnlineTileSourceBase(
            name,
            3,
            19,
            256,
            ".png",
            arrayOf(
                "https://webst01.is.autonavi.com",
                "https://webst02.is.autonavi.com",
                "https://webst03.is.autonavi.com",
                "https://webst04.is.autonavi.com",
            ),
            "AutoNavi",
            TileSourcePolicy(2, 0),
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + "/appmaptile?style=" + style +
                    "&x=" + MapTileIndex.getX(pMapTileIndex) +
                    "&y=" + MapTileIndex.getY(pMapTileIndex) +
                    "&z=" + MapTileIndex.getZoom(pMapTileIndex)
            }
        }
    }

    val SOURCES: List<Source> = listOf(
        Source(Prefs.TILE_SOURCE_AMAP_ROAD, true) { amapSource("AMapRoad", 7) },
        Source(Prefs.TILE_SOURCE_AMAP_SATELLITE, true) { amapSource("AMapSatellite", 6) },
        Source(Prefs.TILE_SOURCE_OSM, false) { TileSourceFactory.MAPNIK },
    )

    fun byId(id: String): Source = SOURCES.firstOrNull { it.id == id } ?: SOURCES[0]

    fun labelFor(id: String, context: android.content.Context): String {
        val resources = context.resources
        return when (id) {
            Prefs.TILE_SOURCE_AMAP_SATELLITE -> resources.getString(dev.ghostloc.offline.R.string.tile_source_amap_satellite)
            Prefs.TILE_SOURCE_OSM -> resources.getString(dev.ghostloc.offline.R.string.tile_source_osm)
            else -> resources.getString(dev.ghostloc.offline.R.string.tile_source_amap_road)
        }
    }
}
