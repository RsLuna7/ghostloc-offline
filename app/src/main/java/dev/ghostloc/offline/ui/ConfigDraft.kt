package dev.ghostloc.offline.ui

import dev.ghostloc.offline.core.Prefs

/**
 * In-memory draft of every setting shown in the four tabs. Fragments push
 * user edits here as they happen; the activity persists the whole draft on
 * start/stop/save, mirroring the old single-screen commit behaviour.
 */
object ConfigDraft {

    interface Listener {
        fun onDraftChanged(source: Any?)
    }

    // Raw editor text; parsing/validation happens at commit time.
    var latText: String = Prefs.DEFAULT_LAT.toString()
    var lonText: String = Prefs.DEFAULT_LON.toString()
    var speedText: String = Prefs.DEFAULT_SPEED.toString()
    var altitudeText: String = Prefs.DEFAULT_ALTITUDE.toString()
    var accuracyText: String = Prefs.DEFAULT_ACCURACY.toString()
    var bearingText: String = Prefs.DEFAULT_BEARING.toString()
    var mccText: String = ""
    var mncText: String = ""
    var countryText: String = ""
    var operatorText: String = ""
    var gnssSatText: String = Prefs.DEFAULT_GNSS_SAT_COUNT.toString()
    var speedVariationText: String = Prefs.DEFAULT_ROUTE_SPEED_VARIATION.toString()
    var accuracyJitterText: String = Prefs.DEFAULT_ACCURACY_JITTER.toString()
    var altitudeJitterText: String = Prefs.DEFAULT_ALTITUDE_JITTER.toString()
    var routeJsonText: String = "[]"
    var favoritesJsonText: String = "[]"

    var wifiShield: Boolean = true
    var cellShield: Boolean = true
    var mockShield: Boolean = true
    var movement: Boolean = false
    var nrEnabled: Boolean = true
    var nmeaEnabled: Boolean = true
    var agpsEnabled: Boolean = true
    var vendorHooksEnabled: Boolean = true
    var routeLoop: Boolean = false
    var routeAutoBearing: Boolean = true

    // UI-only preferences.
    var tileSourceId: String = Prefs.TILE_SOURCE_AMAP_ROAD
    var pickupGcj02Correct: Boolean = true

    private val listeners = ArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Notify other tabs that shared values (e.g. coordinates) moved. */
    fun notifyChanged(source: Any?) {
        for (listener in ArrayList(listeners)) {
            if (listener !== source) {
                listener.onDraftChanged(source)
            }
        }
    }

    fun doubleOf(text: String, fallback: Double): Double {
        val value = text.trim()
        if (value.isEmpty()) {
            return fallback
        }
        return try {
            value.toDouble()
        } catch (_: Throwable) {
            fallback
        }
    }

    fun floatOf(text: String, fallback: Float): Float = doubleOf(text, fallback.toDouble()).toFloat()

    fun intOf(text: String, fallback: Int): Int {
        val value = text.trim()
        if (value.isEmpty()) {
            return fallback
        }
        return try {
            value.toInt()
        } catch (_: Throwable) {
            fallback
        }
    }

    fun latOr(fallback: Double = Prefs.DEFAULT_LAT): Double = doubleOf(latText, fallback)
    fun lonOr(fallback: Double = Prefs.DEFAULT_LON): Double = doubleOf(lonText, fallback)
}
