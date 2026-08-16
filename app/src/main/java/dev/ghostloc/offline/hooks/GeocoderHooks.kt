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
                    ModuleRuntime.bridge?.hook(method, GeocoderHooker::class.java)
                }
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Geocoder hook failed", throwable)
        }
    }
}

class GeocoderHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active() || !ModuleRuntime.mockShield()) {
                return
            }
            if ((callback.member as? Method)?.returnType == Void.TYPE) {
                AddressSynth.dispatchGeocodeListener(callback.args, AddressSynth.makeAddressList())
                callback.returnAndSkip(null)
            } else {
                callback.returnAndSkip(AddressSynth.makeAddressList())
            }
        }
    }
}
