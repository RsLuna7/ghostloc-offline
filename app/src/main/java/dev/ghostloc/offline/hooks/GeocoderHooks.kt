package dev.ghostloc.offline.hooks

import android.location.Geocoder
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.synth.AddressSynth
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

object GeocoderHooks {

    fun install() {
        try {
            for (method in Geocoder::class.java.declaredMethods) {
                if (method.name == "getFromLocation" || method.name == "getFromLocationName") {
                    ModuleRuntime.hook(method, GeocoderHooker)
                }
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Geocoder hook failed", throwable)
        }
    }
}

object GeocoderHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active() || !ModuleRuntime.mockShield()) {
            return chain.proceed()
        }
        if ((chain.executable as? Method)?.returnType == Void.TYPE) {
            AddressSynth.dispatchGeocodeListener(HookSupport.argArray(chain), AddressSynth.makeAddressList())
            return null
        }
        return AddressSynth.makeAddressList()
    }
}
