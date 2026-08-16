package dev.ghostloc.offline.core

import android.location.Location
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical movement math plus GCJ-02 <-> WGS-84 conversion. */
object GeoMath {

    private const val EARTH_RADIUS_METERS = 6371000.0

    fun moveDistance(lat: Double, lon: Double, bearingDeg: Float, distanceMeters: Double): DoubleArray {
        val distance = max(0.0, distanceMeters)
        val angular = distance / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDeg.toDouble())
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val sinLat1 = sin(lat1)
        val cosLat1 = cos(lat1)
        val sinAngular = sin(angular)
        val cosAngular = cos(angular)
        val lat2 = asin(sinLat1 * cosAngular + cosLat1 * sinAngular * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sinAngular * cosLat1,
            cosAngular - sinLat1 * sin(lat2),
        )
        return doubleArrayOf(Math.toDegrees(lat2), normalizeLon(Math.toDegrees(lon2)))
    }

    fun distanceBetween(startLat: Double, startLon: Double, endLat: Double, endLon: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(startLat, startLon, endLat, endLon, result)
        return result[0].toDouble()
    }

    fun bearingBetween(startLat: Double, startLon: Double, endLat: Double, endLon: Double): Float {
        val result = FloatArray(2)
        Location.distanceBetween(startLat, startLon, endLat, endLon, result)
        return result[1]
    }

    fun interpolate(start: Double, end: Double, fraction: Double): Double {
        return start + (end - start) * fraction
    }

    fun interpolateLongitude(start: Double, end: Double, fraction: Double): Double {
        val delta = normalizeLon(end - start)
        return normalizeLon(start + delta * fraction)
    }

    fun normalizeLon(lon: Double): Double {
        val normalized = (lon + 540.0) % 360.0 - 180.0
        return if (normalized == -180.0) 180.0 else normalized
    }

    // ---- GCJ-02 <-> WGS-84 ----
    // AMap/Tencent tiles are drawn in GCJ-02. GPS-level spoof output must be
    // WGS-84, so map-picked coordinates need the inverse transform below.

    private const val GCJ_A = 6378245.0
    private const val GCJ_EE = 0.00669342162296594323

    /** True inside the rough mainland bounding box where GCJ-02 offsets apply. */
    fun inChina(lat: Double, lon: Double): Boolean {
        return lat in 0.8293..55.8271 && lon in 72.004..137.8347
    }

    fun wgs84ToGcj02(wgsLat: Double, wgsLon: Double): DoubleArray {
        if (!inChina(wgsLat, wgsLon)) {
            return doubleArrayOf(wgsLat, wgsLon)
        }
        val dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0)
        val dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - GCJ_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val dLatAdjusted = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * PI)
        val dLonAdjusted = (dLon * 180.0) / (GCJ_A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(wgsLat + dLatAdjusted, wgsLon + dLonAdjusted)
    }

    fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): DoubleArray {
        if (!inChina(gcjLat, gcjLon)) {
            return doubleArrayOf(gcjLat, gcjLon)
        }
        // Iterative inverse: start from gcj as the wgs guess, converge when the
        // forward transform of the guess reproduces the gcj input.
        var wgsLat = gcjLat
        var wgsLon = gcjLon
        var dLat = 0.0
        var dLon = 0.0
        for (i in 0 until 6) {
            val guess = wgs84ToGcj02(wgsLat, wgsLon)
            dLat = guess[0] - gcjLat
            dLon = guess[1] - gcjLon
            wgsLat -= dLat
            wgsLon -= dLon
            if (abs(dLat) < 1e-9 && abs(dLon) < 1e-9) {
                break
            }
        }
        return doubleArrayOf(wgsLat, wgsLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    fun clamp(value: Double, minimum: Double, maximum: Double): Double {
        return max(minimum, min(maximum, value))
    }
}
