package dev.ghostloc.offline.core

/**
 * Shared preference keys and defaults. The "settings" group is stored through
 * libxposed remote preferences so the UI process and every hooked process read
 * the same state. Key values must stay stable: existing installations keep
 * their preferences across module updates.
 */
object Prefs {
    const val GROUP = "settings"

    const val ACTIVE = "active"
    const val LAT = "lat"
    const val LON = "lon"
    const val SPEED = "speed"
    const val ALTITUDE = "altitude"
    const val ACCURACY = "accuracy"
    const val BEARING = "bearing"
    const val WIFI_SHIELD = "wifi_shield"
    const val CELL_SHIELD = "cell_shield"
    const val MOCK_SHIELD = "mock_shield"
    const val CELL_MCC = "cell_mcc"
    const val CELL_MNC = "cell_mnc"
    const val CELL_COUNTRY_ISO = "cell_country_iso"
    const val CELL_OPERATOR_NAME = "cell_operator_name"
    const val CELL_ENABLE_NR = "cell_enable_nr"
    const val GNSS_SAT_COUNT = "gnss_sat_count"
    const val NMEA_ENABLED = "nmea_enabled"
    const val AGPS_ENABLED = "agps_enabled"
    const val VENDOR_HOOKS_ENABLED = "vendor_hooks_enabled"
    const val MOVEMENT = "movement"
    const val STARTED_AT = "started_at"
    const val FAVORITES = "favorites"
    const val ROUTE_POINTS = "route_points"
    const val ROUTE_LOOP = "route_loop"
    const val ROUTE_AUTO_BEARING = "route_auto_bearing"
    const val ROUTE_PAUSED = "route_paused"
    const val ROUTE_PAUSE_ELAPSED_MS = "route_pause_elapsed_ms"
    const val ROUTE_SPEED_VARIATION = "route_speed_variation"
    const val ACCURACY_JITTER = "accuracy_jitter"
    const val ALTITUDE_JITTER = "altitude_jitter"
    const val DIAG_RECENT_HITS = "diag_recent_hits"
    const val DIAG_RECENT_ERRORS = "diag_recent_errors"
    const val DIAG_LAST_PROCESS = "diag_last_process"
    const val LAST_PROBE_REPORT = "last_probe_report"
    const val LAST_PROBE_OK = "last_probe_ok"
    const val LAST_PROBE_AT = "last_probe_at"
    const val LAST_STATUS = "last_status"

    // UI-only settings, read exclusively by the app process.
    const val UI_TILE_SOURCE = "ui_tile_source"
    const val UI_PICKUP_GCJ02_CORRECT = "ui_pickup_gcj02_correct"
    const val UI_AMAP_SEARCH_KEY = "ui_amap_search_key"

    const val TILE_SOURCE_AMAP_ROAD = "amap_road"
    const val TILE_SOURCE_AMAP_SATELLITE = "amap_satellite"
    const val TILE_SOURCE_OSM = "osm"

    const val DEFAULT_LAT = 39.9042
    const val DEFAULT_LON = 116.4074
    const val DEFAULT_SPEED = 3.05f
    const val DEFAULT_ALTITUDE = 80.0
    const val DEFAULT_ACCURACY = 25.0f
    const val DEFAULT_BEARING = 0.0f
    const val DEFAULT_GNSS_SAT_COUNT = 12
    const val DEFAULT_ROUTE_SPEED_VARIATION = 0.15f
    const val DEFAULT_ACCURACY_JITTER = 2.5f
    const val DEFAULT_ALTITUDE_JITTER = 3.0f
}
