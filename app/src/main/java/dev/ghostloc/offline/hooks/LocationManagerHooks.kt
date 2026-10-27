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
                        ModuleRuntime.hook(method, GpsStatusHooker)
                    name == "registerGnssStatusCallback" || name == "addGpsStatusListener" ->
                        ModuleRuntime.hook(method, GnssStatusRegistrationHooker)
                    name == "addNmeaListener" ->
                        ModuleRuntime.hook(method, NmeaListenerHooker)
                }
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("LocationManager hook failed", throwable)
        }
    }
}

object GpsStatusHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.active()) {
            return GnssSynth.makeGpsStatus()
        }
        return chain.proceed()
    }
}

object GnssStatusRegistrationHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        val name = (chain.executable as? Method)?.name ?: ""
        val handled = if (name == "addGpsStatusListener") {
            GnssSynth.dispatchLegacyGpsStatus(HookSupport.argArray(chain))
        } else {
            GnssSynth.dispatchGnssStatusRegistration(HookSupport.argArray(chain))
        }
        return if (handled) true else chain.proceed()
    }
}

object NmeaListenerHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.active() && NmeaSynth.dispatchNmeaListeners(HookSupport.argArray(chain))) {
            return true
        }
        return chain.proceed()
    }
}
