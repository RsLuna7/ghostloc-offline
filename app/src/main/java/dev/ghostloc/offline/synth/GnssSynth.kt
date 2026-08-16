package dev.ghostloc.offline.synth

import android.content.SharedPreferences
import android.location.GnssStatus
import android.location.GpsStatus
import android.location.LocationManager
import android.os.Handler
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.newInstance
import dev.ghostloc.offline.core.ModuleRuntime.setFieldIfPresent
import dev.ghostloc.offline.core.ModuleRuntime.tryInvoke
import dev.ghostloc.offline.core.Prefs
import dev.ghostloc.offline.core.SpoofState
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/**
 * Stable satellite constellation synthesis plus GNSS/GPS-status callback
 * dispatch, so GPS environment reads stay consistent with the spoofed fix.
 */
object GnssSynth {

    data class SatelliteProfile(
        val constellationType: Int,
        val svid: Int,
        val cn0DbHz: Float,
        val elevationDegrees: Float,
        val azimuthDegrees: Float,
        val carrierFrequencyHz: Float,
        val usedInFix: Boolean,
    )

    data class GnssProfile(
        val satellites: List<SatelliteProfile>,
        val nmeaEnabled: Boolean,
        val agpsEnabled: Boolean,
    )

    fun makeGnssStatus(): GnssStatus? {
        val profile = currentGnssProfile()
        val status = newInstance(GnssStatus::class.java) ?: return null
        val count = profile.satellites.size
        val svidWithFlags = IntArray(count)
        val cn0 = FloatArray(count)
        val elevations = FloatArray(count)
        val azimuths = FloatArray(count)
        val carrierFrequencies = FloatArray(count)
        val basebandCn0 = FloatArray(count)
        for (i in 0 until count) {
            val satellite = profile.satellites[i]
            svidWithFlags[i] = encodeGnssSvidWithFlags(satellite)
            cn0[i] = satellite.cn0DbHz
            elevations[i] = satellite.elevationDegrees
            azimuths[i] = satellite.azimuthDegrees
            carrierFrequencies[i] = satellite.carrierFrequencyHz
            basebandCn0[i] = satellite.cn0DbHz - 1.5f
        }
        setFieldIfPresent(status, "mSvCount", count)
        setFieldIfPresent(status, "mSvidWithFlags", svidWithFlags)
        setFieldIfPresent(status, "mCn0DbHzs", cn0)
        setFieldIfPresent(status, "mElevations", elevations)
        setFieldIfPresent(status, "mAzimuths", azimuths)
        setFieldIfPresent(status, "mCarrierFrequencies", carrierFrequencies)
        setFieldIfPresent(status, "mBasebandCn0DbHzs", basebandCn0)
        return status
    }

    fun makeGpsStatus(): GpsStatus? {
        val gnssStatus = makeGnssStatus() ?: return null
        return try {
            GpsStatus.create(gnssStatus, 1100)
        } catch (_: Throwable) {
            null
        }
    }

    private fun encodeGnssSvidWithFlags(satellite: SatelliteProfile): Int {
        var flags = 0x1 or 0x2 or 0x8 or 0x10
        if (satellite.usedInFix) {
            flags = flags or 0x4
        }
        return (satellite.svid shl 8) or (satellite.constellationType shl 4) or flags
    }

    fun currentGnssProfile(): GnssProfile {
        val prefs: SharedPreferences? = ModuleRuntime.prefs()
        val location = SpoofState.makeLocation(LocationManager.GPS_PROVIDER)
        val configuredCount = prefs?.getInt(Prefs.GNSS_SAT_COUNT, Prefs.DEFAULT_GNSS_SAT_COUNT)
            ?: Prefs.DEFAULT_GNSS_SAT_COUNT
        val seed = mixSeed(
            location.latitude, location.longitude,
            "gnss", configuredCount.toString(), "profile",
        )
        val satelliteCount = max(4, min(24, configuredCount))
        val satellites = ArrayList<SatelliteProfile>(satelliteCount)
        for (i in 0 until satelliteCount) {
            val itemSeed = seed.rotateLeft(i + 3) xor (i * 0x9e3779b9L)
            val constellationType = when {
                i % 6 == 4 -> GnssStatus.CONSTELLATION_BEIDOU
                i % 6 == 5 -> GnssStatus.CONSTELLATION_GLONASS
                else -> GnssStatus.CONSTELLATION_GPS
            }
            val svid = 3 + positiveInt(itemSeed, 28) + i
            val cn0 = 28.0f + positiveInt(itemSeed.rotateLeft(7), 18)
            val elevation = 12.0f + positiveInt(itemSeed.rotateLeft(11), 70)
            val azimuth = positiveInt(itemSeed.rotateLeft(13), 360).toFloat()
            val usedInFix = i < min(8, satelliteCount)
            val carrierFrequency = if (constellationType == GnssStatus.CONSTELLATION_GLONASS) {
                1_602_000_000f
            } else {
                1_575_420_000f
            }
            satellites.add(
                SatelliteProfile(constellationType, svid, cn0, elevation, azimuth, carrierFrequency, usedInFix)
            )
        }
        return GnssProfile(
            satellites,
            prefs?.getBoolean(Prefs.NMEA_ENABLED, true) ?: true,
            prefs?.getBoolean(Prefs.AGPS_ENABLED, true) ?: true,
        )
    }

    /** Registration-time dispatch for registerGnssStatusCallback / listeners. */
    fun dispatchGnssStatusRegistration(args: Array<Any?>?): Boolean {
        if (args == null) {
            return false
        }
        var executor: Executor? = null
        var handler: Handler? = null
        var callback: Any? = null
        for (arg in args) {
            if (arg is Executor) {
                executor = arg
            } else if (arg is Handler) {
                handler = arg
            } else if (arg != null && ModuleRuntime.hasMethod(arg.javaClass, "onSatelliteStatusChanged", GnssStatus::class.java)) {
                callback = arg
            }
        }
        val target = callback ?: return false
        val status = makeGnssStatus()
        dispatchAsync(executor, handler) {
            tryInvoke(target, "onStarted", emptyArray(), emptyArray())
            tryInvoke(target, "onFirstFix", arrayOf(java.lang.Integer.TYPE), arrayOf(1100))
            if (status != null) {
                tryInvoke(target, "onSatelliteStatusChanged", arrayOf(GnssStatus::class.java), arrayOf(status))
            }
        }
        return true
    }

    fun dispatchLegacyGpsStatus(args: Array<Any?>?): Boolean {
        if (args == null) {
            return false
        }
        var listener: Any? = null
        for (arg in args) {
            if (arg != null && ModuleRuntime.hasMethod(arg.javaClass, "onGpsStatusChanged", java.lang.Integer.TYPE)) {
                listener = arg
                break
            }
        }
        val target = listener ?: return false
        dispatchAsync(null, null) {
            tryInvoke(target, "onGpsStatusChanged", arrayOf(java.lang.Integer.TYPE), arrayOf(GpsStatus.GPS_EVENT_STARTED))
            tryInvoke(target, "onGpsStatusChanged", arrayOf(java.lang.Integer.TYPE), arrayOf(GpsStatus.GPS_EVENT_FIRST_FIX))
            tryInvoke(target, "onGpsStatusChanged", arrayOf(java.lang.Integer.TYPE), arrayOf(GpsStatus.GPS_EVENT_SATELLITE_STATUS))
        }
        return true
    }

    fun dispatchAsync(executor: Executor?, handler: Handler?, task: Runnable) {
        when {
            executor != null -> executor.execute(task)
            handler != null -> handler.post(task)
            else -> task.run()
        }
    }

    private fun mixSeed(lat: Double, lon: Double, vararg parts: String): Long {
        // Golden-ratio multiplier in decimal form (Java: 0x9e3779b97f4a7c15L).
        val goldenRatio = -7046029254386353131L
        var seed = lat.toBits() * goldenRatio
        seed = seed xor lon.toBits().rotateLeft(29)
        for ((index, part) in parts.withIndex()) {
            seed = seed xor part.hashCode().toLong().rotateLeft(13 + index * 6)
        }
        return seed xor 0x51ed270b7f4a7c15L
    }

    private fun positiveInt(seed: Long, bound: Int): Int {
        return if (bound <= 0) 0 else Math.floorMod(seed, bound.toLong()).toInt()
    }
}
