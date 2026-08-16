package dev.ghostloc.offline.hooks

import android.location.LocationManager
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.synth.GnssSynth
import dev.ghostloc.offline.synth.NmeaSynth
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * Public `LocationManager` client-side APIs.
 *
 * Location VALUES are deliberately not spoofed client-side: the binder call
 * is allowed through to system_server, whose hooks answer with live state
 * (see DeepServiceHooks). Client hooks here only cover APIs with no
 * server-side path (GPS/GNSS/NMEA status), using boot-time configuration.
 */
object LocationManagerHooks {

    fun install() {
        try {
            val cls = LocationManager::class.java
            for (method in cls.declaredMethods) {
                val name = method.name
                when {
                    name == "getGpsStatus" ->
                        ModuleRuntime.bridge?.hook(method, GpsStatusHooker::class.java)
                    name == "registerGnssStatusCallback" || name == "addGpsStatusListener" ->
                        ModuleRuntime.bridge?.hook(method, GnssStatusRegistrationHooker::class.java)
                    name == "addNmeaListener" ->
                        ModuleRuntime.bridge?.hook(method, NmeaListenerHooker::class.java)
                }
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("LocationManager hook failed", throwable)
        }
    }
}

class GpsStatusHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.active()) {
                callback.returnAndSkip(GnssSynth.makeGpsStatus())
            }
        }
    }
}

class GnssStatusRegistrationHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            val name = (callback.member as? Method)?.name ?: ""
            val handled = if (name == "addGpsStatusListener") {
                GnssSynth.dispatchLegacyGpsStatus(callback.args)
            } else {
                GnssSynth.dispatchGnssStatusRegistration(callback.args)
            }
            if (handled) {
                callback.returnAndSkip(true)
            }
        }
    }
}

class NmeaListenerHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.active() && NmeaSynth.dispatchNmeaListeners(callback.args)) {
                callback.returnAndSkip(true)
            }
        }
    }
}
