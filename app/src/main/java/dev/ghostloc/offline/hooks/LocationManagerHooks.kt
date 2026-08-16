package dev.ghostloc.offline.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import dev.ghostloc.offline.core.Bridge
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.SpoofState
import dev.ghostloc.offline.core.ModuleRuntime.BRIDGE_PROVIDER
import dev.ghostloc.offline.synth.GnssSynth
import dev.ghostloc.offline.synth.NmeaSynth
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.Executor
import java.util.function.Consumer

/** Public `LocationManager` API surface plus the bridge entry point. */
object LocationManagerHooks {

    fun install() {
        try {
            val cls = LocationManager::class.java
            for (method in cls.declaredMethods) {
                val name = method.name
                when {
                    name == "getLastKnownLocation" && method.returnType == Location::class.java ->
                        ModuleRuntime.bridge?.hook(method, LastKnownLocationHooker::class.java)
                    name == "requestLocationUpdates" || name == "getCurrentLocation" ->
                        ModuleRuntime.bridge?.hook(method, RequestUpdatesHooker::class.java)
                    name == "getProviders" || name == "getAllProviders" ->
                        ModuleRuntime.bridge?.hook(method, ProvidersHooker::class.java)
                    name == "isProviderEnabled" ->
                        ModuleRuntime.bridge?.hook(method, ProviderEnabledHooker::class.java)
                    name == "getBestProvider" ->
                        ModuleRuntime.bridge?.hook(method, BestProviderHooker::class.java)
                    name == "getGpsStatus" ->
                        ModuleRuntime.bridge?.hook(method, GpsStatusHooker::class.java)
                    name == "registerGnssStatusCallback" || name == "addGpsStatusListener" ->
                        ModuleRuntime.bridge?.hook(method, GnssStatusRegistrationHooker::class.java)
                    name == "addNmeaListener" ->
                        ModuleRuntime.bridge?.hook(method, NmeaListenerHooker::class.java)
                    name == "sendExtraCommand" ->
                        ModuleRuntime.bridge?.hook(method, ExtraCommandHooker::class.java)
                }
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("LocationManager hook failed", throwable)
        }
    }
}

class LastKnownLocationHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            callback.returnAndSkip(SpoofState.makeLocation(HookSupport.providerFrom(callback.args)))
        }
    }
}

class RequestUpdatesHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            val args = callback.args ?: return
            if (HookSupport.isCancellationSignalSet(args)) {
                return
            }
            val location = SpoofState.makeLocation(HookSupport.providerFrom(args))
            var executor: Executor? = null
            for (arg in args) {
                if (arg is Executor) {
                    executor = arg
                }
                if (arg is LocationListener) {
                    try {
                        arg.onLocationChanged(location)
                    } catch (throwable: Throwable) {
                        ModuleRuntime.log("listener dispatch failed", throwable)
                    }
                }
                if (arg is Consumer<*>) {
                    dispatchConsumer(executor, arg, location)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun dispatchConsumer(executor: Executor?, consumer: Consumer<*>, location: Location) {
            val task = Runnable {
                try {
                    (consumer as Consumer<Location>).accept(location)
                } catch (throwable: Throwable) {
                    ModuleRuntime.log("consumer dispatch failed", throwable)
                }
            }
            executor?.execute(task) ?: task.run()
        }
    }
}

class ProvidersHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.active()) {
                callback.returnAndSkip(
                    listOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER,
                        LocationManager.FUSED_PROVIDER,
                    )
                )
            }
        }
    }
}

class ProviderEnabledHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.active() && HookSupport.isKnownProviderArg(callback.args)) {
                callback.returnAndSkip(true)
            }
        }
    }
}

class BestProviderHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.active()) {
                callback.returnAndSkip(LocationManager.GPS_PROVIDER)
            }
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

class ExtraCommandHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            val args = callback.args ?: return
            if (args.size < 2 || args[0] != BRIDGE_PROVIDER) {
                return
            }
            val command = args[1]?.toString() ?: return
            var bundle: Bundle? = null
            for (arg in args) {
                if (arg is Bundle) {
                    bundle = arg
                }
            }
            Bridge.handle(command, bundle)
            callback.returnAndSkip(true)
        }
    }
}
