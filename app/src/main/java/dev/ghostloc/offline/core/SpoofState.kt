package dev.ghostloc.offline.core

import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Computes the spoofed location for each hook hit from the current settings. */
object SpoofState {

    data class RoutePoint(val latitude: Double, val longitude: Double)

    data class LocationState(
        val provider: String,
        val latitude: Double,
        val longitude: Double,
        val speed: Float,
        val altitude: Double,
        val accuracy: Float,
        val bearing: Float,
    )

    fun makeLocation(provider: String?): Location {
        val p = ModuleRuntime.prefs()
        val effectiveProvider = provider ?: LocationManager.GPS_PROVIDER
        val state = if (p != null) {
            currentLocationState(effectiveProvider, p)
        } else {
            LocationState(
                effectiveProvider,
                Prefs.DEFAULT_LAT,
                Prefs.DEFAULT_LON,
                Prefs.DEFAULT_SPEED,
                Prefs.DEFAULT_ALTITUDE,
                Prefs.DEFAULT_ACCURACY,
                Prefs.DEFAULT_BEARING,
            )
        }
        val location = Location(state.provider)
        location.latitude = state.latitude
        location.longitude = state.longitude
        location.speed = state.speed
        location.altitude = state.altitude
        location.accuracy = state.accuracy
        location.bearing = state.bearing
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        location.setVerticalAccuracyMeters(5.0f)
        location.setSpeedAccuracyMetersPerSecond(0.1f)
        location.setBearingAccuracyDegrees(1.0f)
        val extras = location.extras ?: Bundle()
        extras.putBoolean(ModuleRuntime.FAKED_EXTRA, true)
        location.extras = extras
        return location
    }

    private fun currentLocationState(provider: String, p: SharedPreferences): LocationState {
        var lat = Double.fromBits(p.getLong(Prefs.LAT, Prefs.DEFAULT_LAT.toRawBits()))
        var lon = Double.fromBits(p.getLong(Prefs.LON, Prefs.DEFAULT_LON.toRawBits()))
        val baseSpeed = p.getFloat(Prefs.SPEED, Prefs.DEFAULT_SPEED)
        val baseBearing = p.getFloat(Prefs.BEARING, Prefs.DEFAULT_BEARING)
        val baseAltitude = Double.fromBits(p.getLong(Prefs.ALTITUDE, Prefs.DEFAULT_ALTITUDE.toRawBits()))
        val baseAccuracy = p.getFloat(Prefs.ACCURACY, Prefs.DEFAULT_ACCURACY)
        val elapsedSeconds = routeElapsedSeconds(p)
        val speedVariation = p.getFloat(Prefs.ROUTE_SPEED_VARIATION, Prefs.DEFAULT_ROUTE_SPEED_VARIATION)
        val effectiveSpeed = variedSpeed(baseSpeed, speedVariation, elapsedSeconds)
        if (p.getBoolean(Prefs.MOVEMENT, false)) {
            val route = parseRoutePoints(p.getString(Prefs.ROUTE_POINTS, ""))
            if (route.size >= 2) {
                val routeState = routeLocationState(
                    provider, p, route, elapsedSeconds, baseSpeed, speedVariation,
                    p.getBoolean(Prefs.ROUTE_LOOP, false),
                    p.getBoolean(Prefs.ROUTE_AUTO_BEARING, true),
                    baseBearing, baseAltitude, baseAccuracy,
                )
                if (routeState != null) {
                    return routeState
                }
            }
            val moved = GeoMath.moveDistance(
                lat, lon, baseBearing,
                integratedDistanceMeters(baseSpeed, speedVariation, elapsedSeconds),
            )
            lat = moved[0]
            lon = moved[1]
        }
        return LocationState(
            provider,
            lat,
            lon,
            effectiveSpeed,
            applyAltitudeJitter(p, baseAltitude, elapsedSeconds),
            applyAccuracyJitter(p, baseAccuracy, elapsedSeconds),
            baseBearing,
        )
    }

    private fun routeLocationState(
        provider: String,
        p: SharedPreferences,
        route: List<RoutePoint>,
        elapsedSeconds: Double,
        baseSpeed: Float,
        speedVariation: Float,
        loop: Boolean,
        autoBearing: Boolean,
        fallbackBearing: Float,
        baseAltitude: Double,
        baseAccuracy: Float,
    ): LocationState? {
        var totalDistance = 0.0
        for (i in 0 until route.size - 1) {
            totalDistance += segmentDistance(route[i], route[i + 1])
        }
        if (totalDistance <= 0.5) {
            val point = route[0]
            return LocationState(
                provider, point.latitude, point.longitude,
                variedSpeed(baseSpeed, speedVariation, elapsedSeconds),
                applyAltitudeJitter(p, baseAltitude, elapsedSeconds),
                applyAccuracyJitter(p, baseAccuracy, elapsedSeconds),
                fallbackBearing,
            )
        }
        var traveled = integratedDistanceMeters(baseSpeed, speedVariation, elapsedSeconds)
        traveled = if (loop) {
            traveled % totalDistance
        } else {
            min(traveled, totalDistance)
        }
        for (i in 0 until route.size - 1) {
            val start = route[i]
            val end = route[i + 1]
            val segmentDistance = segmentDistance(start, end)
            if (i == route.size - 2 || traveled <= segmentDistance) {
                val fraction = if (segmentDistance <= 0.0) {
                    0.0
                } else {
                    GeoMath.clamp(traveled / segmentDistance, 0.0, 1.0)
                }
                val lat = GeoMath.interpolate(start.latitude, end.latitude, fraction)
                val lon = GeoMath.interpolateLongitude(start.longitude, end.longitude, fraction)
                val bearing = if (autoBearing) segmentBearing(start, end) else fallbackBearing
                return LocationState(
                    provider, lat, lon,
                    variedSpeed(baseSpeed, speedVariation, elapsedSeconds),
                    applyAltitudeJitter(p, baseAltitude, elapsedSeconds),
                    applyAccuracyJitter(p, baseAccuracy, elapsedSeconds),
                    bearing,
                )
            }
            traveled -= segmentDistance
        }
        val end = route[route.size - 1]
        return LocationState(
            provider, end.latitude, end.longitude,
            variedSpeed(baseSpeed, speedVariation, elapsedSeconds),
            applyAltitudeJitter(p, baseAltitude, elapsedSeconds),
            applyAccuracyJitter(p, baseAccuracy, elapsedSeconds),
            fallbackBearing,
        )
    }

    fun parseRoutePoints(raw: String?): List<RoutePoint> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        val points = ArrayList<RoutePoint>()
        return try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                when (val value = array.get(i)) {
                    is JSONObject -> {
                        if (value.has("lat") && value.has("lon")) {
                            points.add(RoutePoint(value.optDouble("lat"), value.optDouble("lon")))
                        }
                    }
                    is JSONArray -> {
                        if (value.length() >= 2) {
                            points.add(RoutePoint(value.optDouble(0), value.optDouble(1)))
                        }
                    }
                }
            }
            points
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun routeElapsedSeconds(p: SharedPreferences): Double {
        val elapsedMs = if (p.getBoolean(Prefs.ROUTE_PAUSED, false)) {
            p.getLong(Prefs.ROUTE_PAUSE_ELAPSED_MS, 0L)
        } else {
            max(0L, System.currentTimeMillis() - p.getLong(Prefs.STARTED_AT, 0L))
        }
        return elapsedMs / 1000.0
    }

    private fun variedSpeed(baseSpeed: Float, variation: Float, elapsedSeconds: Double): Float {
        val clamped = max(0.0f, min(0.75f, variation))
        val multiplier = 1.0 + clamped * sin(elapsedSeconds / 18.0)
        return max(0.0, baseSpeed * multiplier).toFloat()
    }

    private fun integratedDistanceMeters(baseSpeed: Float, variation: Float, elapsedSeconds: Double): Double {
        val clamped = max(0.0f, min(0.75f, variation))
        return max(
            0.0,
            baseSpeed * elapsedSeconds +
                baseSpeed * clamped * 18.0 * (1.0 - cos(elapsedSeconds / 18.0)),
        )
    }

    private fun applyAccuracyJitter(p: SharedPreferences, baseAccuracy: Float, elapsedSeconds: Double): Float {
        val jitter = p.getFloat(Prefs.ACCURACY_JITTER, Prefs.DEFAULT_ACCURACY_JITTER)
        if (jitter <= 0.0f) {
            return baseAccuracy
        }
        return max(1.0f, (baseAccuracy + sin(elapsedSeconds / 11.0) * jitter).toFloat())
    }

    private fun applyAltitudeJitter(p: SharedPreferences, baseAltitude: Double, elapsedSeconds: Double): Double {
        val jitter = p.getFloat(Prefs.ALTITUDE_JITTER, Prefs.DEFAULT_ALTITUDE_JITTER)
        if (jitter <= 0.0f) {
            return baseAltitude
        }
        return baseAltitude + cos(elapsedSeconds / 13.0) * jitter
    }

    private fun segmentDistance(start: RoutePoint, end: RoutePoint): Double {
        return GeoMath.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude)
    }

    private fun segmentBearing(start: RoutePoint, end: RoutePoint): Float {
        return GeoMath.bearingBetween(start.latitude, start.longitude, end.latitude, end.longitude)
    }
}
