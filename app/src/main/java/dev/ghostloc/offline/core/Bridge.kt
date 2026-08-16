package dev.ghostloc.offline.core

import android.os.Bundle

/**
 * Command bridge reached through
 * `LocationManager.sendExtraCommand("ghostloc", command, bundle)`.
 *
 * The hook lives on the system_server implementation of sendExtraCommand, so
 * this handler normally runs with system privileges inside system_server and
 * persists every write through [StateStore], which all hooked processes then
 * read. Writes arriving in other processes (no permission for the state file)
 * are tolerated and logged.
 */
object Bridge {

    fun handle(command: String, bundle: Bundle?) {
        val data = bundle ?: Bundle.EMPTY
        val mutations = when (command) {
            "start" -> buildMap {
                putLocation(data)
                put(Prefs.ACTIVE, "true")
                put(Prefs.STARTED_AT, System.currentTimeMillis().toString())
                put(Prefs.LAST_STATUS, "started")
            }
            "stop" -> buildMap {
                put(Prefs.ACTIVE, "false")
                put(Prefs.STARTED_AT, "0")
                put(Prefs.ROUTE_PAUSED, "false")
                put(Prefs.ROUTE_PAUSE_ELAPSED_MS, "0")
                put(Prefs.LAST_STATUS, "stopped")
            }
            "update_location", "move" -> buildMap { putLocation(data) }
            "put_config" -> configFrom(data)
            "set_speed" -> mapOf(Prefs.SPEED to data.getDouble("speed", Prefs.DEFAULT_SPEED.toDouble()).toString())
            "set_altitude" -> mapOf(
                Prefs.ALTITUDE to data.getDouble("altitude", Prefs.DEFAULT_ALTITUDE).toRawBits().toString()
            )
            "set_bearing" -> mapOf(
                Prefs.BEARING to data.getDouble("bearing", Prefs.DEFAULT_BEARING.toDouble()).toString()
            )
            "sync" -> syncFrom(data)
            "is_start" -> {
                data.putBoolean("is_start", ModuleRuntime.active())
                ModuleRuntime.log("bridge query is_start=${ModuleRuntime.active()}")
                return
            }
            else -> return
        }
        if (mutations.isEmpty()) {
            return
        }
        if (StateStore.update(mutations)) {
            ModuleRuntime.log("bridge command handled=$command keys=${mutations.size}")
        } else {
            ModuleRuntime.log("bridge command observed=$command (state file not writable here)")
        }
    }

    private fun MutableMap<String, String>.putLocation(data: Bundle) {
        if (data.containsKey("lat")) {
            put(Prefs.LAT, data.getDouble("lat", ModuleRuntime.currentLat()).toRawBits().toString())
        }
        if (data.containsKey("lon")) {
            put(Prefs.LON, data.getDouble("lon", ModuleRuntime.currentLon()).toRawBits().toString())
        }
        putAll(configFrom(data))
    }

    private fun configFrom(data: Bundle): Map<String, String> {
        val map = HashMap<String, String>()
        if (data.containsKey("speed")) {
            map[Prefs.SPEED] = data.getDouble("speed", Prefs.DEFAULT_SPEED.toDouble()).toString()
        }
        if (data.containsKey("altitude")) {
            map[Prefs.ALTITUDE] = data.getDouble("altitude", Prefs.DEFAULT_ALTITUDE).toRawBits().toString()
        }
        if (data.containsKey("accuracy")) {
            map[Prefs.ACCURACY] = data.getDouble("accuracy", Prefs.DEFAULT_ACCURACY.toDouble()).toString()
        }
        if (data.containsKey("bearing")) {
            map[Prefs.BEARING] = data.getDouble("bearing", Prefs.DEFAULT_BEARING.toDouble()).toString()
        }
        return map
    }

    /** Full-state sync pushed by the UI on every commit. */
    private fun syncFrom(data: Bundle): Map<String, String> {
        val map = HashMap<String, String>(configFrom(data))
        fun bool(key: String, prefKey: String, default: Boolean) {
            if (data.containsKey(key)) map[prefKey] = data.getBoolean(key, default).toString()
        }
        fun str(key: String, prefKey: String, default: String) {
            if (data.containsKey(key)) map[prefKey] = data.getString(key, default) ?: default
        }
        if (data.containsKey("lat")) {
            map[Prefs.LAT] = data.getDouble("lat", ModuleRuntime.currentLat()).toRawBits().toString()
        }
        if (data.containsKey("lon")) {
            map[Prefs.LON] = data.getDouble("lon", ModuleRuntime.currentLon()).toRawBits().toString()
        }
        bool("active", Prefs.ACTIVE, false)
        if (data.containsKey("started_at")) {
            map[Prefs.STARTED_AT] = data.getLong("started_at", 0L).toString()
        }
        bool("movement", Prefs.MOVEMENT, false)
        bool("wifi_shield", Prefs.WIFI_SHIELD, true)
        bool("cell_shield", Prefs.CELL_SHIELD, true)
        bool("mock_shield", Prefs.MOCK_SHIELD, true)
        bool("nr_enabled", Prefs.CELL_ENABLE_NR, true)
        bool("nmea_enabled", Prefs.NMEA_ENABLED, true)
        bool("agps_enabled", Prefs.AGPS_ENABLED, true)
        bool("vendor_hooks_enabled", Prefs.VENDOR_HOOKS_ENABLED, true)
        if (data.containsKey("gnss_sat_count")) {
            map[Prefs.GNSS_SAT_COUNT] = data.getInt("gnss_sat_count", Prefs.DEFAULT_GNSS_SAT_COUNT).toString()
        }
        str("route_points", Prefs.ROUTE_POINTS, "[]")
        bool("route_loop", Prefs.ROUTE_LOOP, false)
        bool("route_auto_bearing", Prefs.ROUTE_AUTO_BEARING, true)
        bool("route_paused", Prefs.ROUTE_PAUSED, false)
        if (data.containsKey("route_pause_elapsed_ms")) {
            map[Prefs.ROUTE_PAUSE_ELAPSED_MS] = data.getLong("route_pause_elapsed_ms", 0L).toString()
        }
        str("favorites", Prefs.FAVORITES, "[]")
        str("cell_mcc", Prefs.CELL_MCC, "")
        str("cell_mnc", Prefs.CELL_MNC, "")
        str("cell_country_iso", Prefs.CELL_COUNTRY_ISO, "")
        str("cell_operator_name", Prefs.CELL_OPERATOR_NAME, "")
        str("last_status", Prefs.LAST_STATUS, "")
        return map
    }
}
