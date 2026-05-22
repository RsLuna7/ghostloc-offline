package dev.ghostloc.offline;

final class SettingsStore {
    static final String GROUP = "settings";
    static final String ACTIVE = "active";
    static final String LAT = "lat";
    static final String LON = "lon";
    static final String SPEED = "speed";
    static final String ALTITUDE = "altitude";
    static final String ACCURACY = "accuracy";
    static final String BEARING = "bearing";
    static final String WIFI_SHIELD = "wifi_shield";
    static final String CELL_SHIELD = "cell_shield";
    static final String MOCK_SHIELD = "mock_shield";
    static final String CELL_MCC = "cell_mcc";
    static final String CELL_MNC = "cell_mnc";
    static final String CELL_COUNTRY_ISO = "cell_country_iso";
    static final String CELL_OPERATOR_NAME = "cell_operator_name";
    static final String CELL_ENABLE_NR = "cell_enable_nr";
    static final String GNSS_SAT_COUNT = "gnss_sat_count";
    static final String NMEA_ENABLED = "nmea_enabled";
    static final String AGPS_ENABLED = "agps_enabled";
    static final String VENDOR_HOOKS_ENABLED = "vendor_hooks_enabled";
    static final String MOVEMENT = "movement";
    static final String STARTED_AT = "started_at";
    static final String FAVORITES = "favorites";
    static final String ROUTE_POINTS = "route_points";
    static final String ROUTE_LOOP = "route_loop";
    static final String ROUTE_AUTO_BEARING = "route_auto_bearing";
    static final String ROUTE_PAUSED = "route_paused";
    static final String ROUTE_PAUSE_ELAPSED_MS = "route_pause_elapsed_ms";
    static final String ROUTE_SPEED_VARIATION = "route_speed_variation";
    static final String ACCURACY_JITTER = "accuracy_jitter";
    static final String ALTITUDE_JITTER = "altitude_jitter";
    static final String DIAG_RECENT_HITS = "diag_recent_hits";
    static final String DIAG_RECENT_ERRORS = "diag_recent_errors";
    static final String DIAG_LAST_PROCESS = "diag_last_process";
    static final String LAST_PROBE_REPORT = "last_probe_report";
    static final String LAST_PROBE_OK = "last_probe_ok";
    static final String LAST_PROBE_AT = "last_probe_at";
    static final String LAST_STATUS = "last_status";

    static final double DEFAULT_LAT = 39.9042;
    static final double DEFAULT_LON = 116.4074;
    static final float DEFAULT_SPEED = 3.05f;
    static final double DEFAULT_ALTITUDE = 80.0;
    static final float DEFAULT_ACCURACY = 25.0f;
    static final float DEFAULT_BEARING = 0.0f;
    static final int DEFAULT_GNSS_SAT_COUNT = 12;
    static final float DEFAULT_ROUTE_SPEED_VARIATION = 0.15f;
    static final float DEFAULT_ACCURACY_JITTER = 2.5f;
    static final float DEFAULT_ALTITUDE_JITTER = 3.0f;

    private SettingsStore() {
    }
}
