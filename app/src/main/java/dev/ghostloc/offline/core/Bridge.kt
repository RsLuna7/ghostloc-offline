package dev.ghostloc.offline.core

import android.content.SharedPreferences
import android.os.Bundle

/**
 * Command bridge reached through
 * `LocationManager.sendExtraCommand("ghostloc", command, bundle)` in any
 * scoped process. Writes land in the remote "settings" group so every hooked
 * process sees them on the next read.
 */
object Bridge {

    fun handle(command: String, bundle: Bundle?) {
        val data = bundle ?: Bundle.EMPTY
        val editor = writablePrefsEditor()
        if (editor == null) {
            if (command == "is_start") {
                data.putBoolean("is_start", ModuleRuntime.active())
            }
            ModuleRuntime.log("bridge command observed=$command")
            return
        }
        when (command) {
            "start" -> {
                putLocation(editor, data)
                editor.putBoolean(Prefs.ACTIVE, true)
                editor.putLong(Prefs.STARTED_AT, System.currentTimeMillis())
            }
            "stop" -> {
                editor.putBoolean(Prefs.ACTIVE, false)
                editor.putLong(Prefs.STARTED_AT, 0L)
            }
            "update_location", "move" -> putLocation(editor, data)
            "put_config" -> putConfig(editor, data)
            "set_speed" -> editor.putFloat(Prefs.SPEED, data.getDouble("speed", Prefs.DEFAULT_SPEED.toDouble()).toFloat())
            "set_altitude" -> editor.putLong(
                Prefs.ALTITUDE,
                data.getDouble("altitude", Prefs.DEFAULT_ALTITUDE).toRawBits(),
            )
            "set_bearing" -> editor.putFloat(Prefs.BEARING, data.getDouble("bearing", Prefs.DEFAULT_BEARING.toDouble()).toFloat())
            "is_start" -> data.putBoolean("is_start", ModuleRuntime.active())
            else -> return
        }
        editor.apply()
        ModuleRuntime.log("bridge command handled=$command")
    }

    private fun writablePrefsEditor(): SharedPreferences.Editor? {
        return try {
            ModuleRuntime.prefs()?.edit()
        } catch (_: UnsupportedOperationException) {
            null
        } catch (throwable: Throwable) {
            ModuleRuntime.log("prefs editor unavailable", throwable)
            null
        }
    }

    private fun putLocation(editor: SharedPreferences.Editor, data: Bundle) {
        editor.putLong(Prefs.LAT, data.getDouble("lat", ModuleRuntime.currentLat()).toRawBits())
        editor.putLong(Prefs.LON, data.getDouble("lon", ModuleRuntime.currentLon()).toRawBits())
        putConfig(editor, data)
    }

    private fun putConfig(editor: SharedPreferences.Editor, data: Bundle) {
        if (data.containsKey("speed")) {
            editor.putFloat(Prefs.SPEED, data.getDouble("speed", Prefs.DEFAULT_SPEED.toDouble()).toFloat())
        }
        if (data.containsKey("altitude")) {
            editor.putLong(Prefs.ALTITUDE, data.getDouble("altitude", Prefs.DEFAULT_ALTITUDE).toRawBits())
        }
        if (data.containsKey("accuracy")) {
            editor.putFloat(Prefs.ACCURACY, data.getDouble("accuracy", Prefs.DEFAULT_ACCURACY.toDouble()).toFloat())
        }
        if (data.containsKey("bearing")) {
            editor.putFloat(Prefs.BEARING, data.getDouble("bearing", Prefs.DEFAULT_BEARING.toDouble()).toFloat())
        }
    }
}
