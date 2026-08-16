package dev.ghostloc.offline.synth

import android.location.Location
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.tryInvoke
import dev.ghostloc.offline.core.SpoofState
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor

/** NMEA sentence synthesis derived from the current spoof state. */
object NmeaSynth {

    fun dispatchNmeaListeners(args: Array<Any?>?): Boolean {
        if (args == null) {
            return false
        }
        var executor: Executor? = null
        var handler: Handler? = null
        var listener: Any? = null
        for (arg in args) {
            if (arg is Executor) {
                executor = arg
            } else if (arg is Handler) {
                handler = arg
            } else if (arg is OnNmeaMessageListener
                || (arg != null && (ModuleRuntime.hasMethod(arg.javaClass, "onNmeaMessage", String::class.java, java.lang.Long.TYPE)
                    || ModuleRuntime.hasMethod(arg.javaClass, "onNmeaReceived", java.lang.Long.TYPE, String::class.java)))
            ) {
                listener = arg
            }
        }
        val target = listener ?: return false
        val profile = GnssSynth.currentGnssProfile()
        if (!profile.nmeaEnabled) {
            return true
        }
        val sentences = makeNmeaSentences(SpoofState.makeLocation(LocationManager.GPS_PROVIDER), profile)
        GnssSynth.dispatchAsync(executor, handler) {
            val timestamp = System.currentTimeMillis()
            for (sentence in sentences) {
                if (!tryInvoke(
                        target, "onNmeaMessage",
                        arrayOf(String::class.java, java.lang.Long.TYPE), arrayOf(sentence, timestamp),
                    )
                ) {
                    tryInvoke(
                        target, "onNmeaReceived",
                        arrayOf(java.lang.Long.TYPE, String::class.java), arrayOf(timestamp, sentence),
                    )
                }
            }
        }
        return true
    }

    fun makeNmeaSentences(location: Location, profile: GnssSynth.GnssProfile): List<String> {
        val sentences = ArrayList<String>()
        val now = System.currentTimeMillis()
        val utcTime = nmeaUtcTime(now)
        val utcDate = nmeaUtcDate(now)
        val lat = nmeaCoordinate(kotlin.math.abs(location.latitude), true)
        val latHemisphere = if (location.latitude >= 0) "N" else "S"
        val lon = nmeaCoordinate(kotlin.math.abs(location.longitude), false)
        val lonHemisphere = if (location.longitude >= 0) "E" else "W"
        sentences.add(
            withNmeaChecksum(
                String.format(
                    Locale.US,
                    "\$GPGGA,%s,%s,%s,%s,%s,1,%02d,0.9,%.1f,M,0.0,M,,",
                    utcTime, lat, latHemisphere, lon, lonHemisphere, profile.satellites.size,
                    location.altitude,
                )
            )
        )
        sentences.add(
            withNmeaChecksum(
                String.format(
                    Locale.US,
                    "\$GPRMC,%s,A,%s,%s,%s,%s,%.1f,%.1f,%s,,",
                    utcTime, lat, latHemisphere, lon, lonHemisphere,
                    location.speed * 1.9438445f, location.bearing, utcDate,
                )
            )
        )
        sentences.add(
            withNmeaChecksum(
                String.format(Locale.US, "\$GPGSA,A,3,03,07,09,16,19,22,26,28,31,14,18,1.2,0.9,1.5")
            )
        )
        val totalMessages = (profile.satellites.size + 3) / 4
        for (messageIndex in 0 until totalMessages) {
            val builder = StringBuilder("\$GPGSV,")
                .append(totalMessages).append(',')
                .append(messageIndex + 1).append(',')
                .append(String.format(Locale.US, "%02d", profile.satellites.size))
            for (i in messageIndex * 4 until minOf(profile.satellites.size, (messageIndex + 1) * 4)) {
                val satellite = profile.satellites[i]
                builder.append(',').append(String.format(Locale.US, "%02d", satellite.svid))
                    .append(',').append(String.format(Locale.US, "%02.0f", satellite.elevationDegrees))
                    .append(',').append(String.format(Locale.US, "%03.0f", satellite.azimuthDegrees))
                    .append(',').append(String.format(Locale.US, "%02.0f", satellite.cn0DbHz))
            }
            sentences.add(withNmeaChecksum(builder.toString()))
        }
        return sentences
    }

    private fun nmeaUtcTime(epochMillis: Long): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = epochMillis
        return String.format(
            Locale.US, "%02d%02d%02d.00",
            calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND),
        )
    }

    private fun nmeaUtcDate(epochMillis: Long): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = epochMillis
        return String.format(
            Locale.US, "%02d%02d%02d",
            calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.YEAR) % 100,
        )
    }

    private fun nmeaCoordinate(absoluteValue: Double, latitude: Boolean): String {
        val degrees = absoluteValue.toInt()
        val minutes = (absoluteValue - degrees) * 60.0
        return if (latitude) {
            String.format(Locale.US, "%02d%07.4f", degrees, minutes)
        } else {
            String.format(Locale.US, "%03d%07.4f", degrees, minutes)
        }
    }

    private fun withNmeaChecksum(sentence: String): String {
        var checksum = 0
        for (i in 1 until sentence.length) {
            checksum = checksum xor sentence[i].code
        }
        return sentence + '*' + String.format(Locale.US, "%02X", checksum)
    }
}
