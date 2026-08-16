package dev.ghostloc.offline.hooks

import android.location.Location
import android.location.LocationListener
import android.os.Binder
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.findClass
import dev.ghostloc.offline.core.ModuleRuntime.hookMethodOnce
import dev.ghostloc.offline.core.ModuleRuntime.tryInvoke
import dev.ghostloc.offline.core.ModuleRuntime.tryStaticInvoke
import dev.ghostloc.offline.core.SpoofState
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * `system_server` location-service path: LocationManagerService internals and
 * the binder stub. These make the spoof survive apps that bypass the public
 * LocationManager API.
 */
object DeepServiceHooks {

    private val TARGET_CLASSES = listOf(
        "com.android.server.location.LocationManagerService",
        "android.location.ILocationManager\$Stub",
        "com.android.server.location.provider.LocationProviderManager",
        "com.android.server.location.provider.AbstractLocationProvider",
        "com.android.server.location.gnss.hal.GnssNative",
    )

    fun install(loader: ClassLoader?) {
        for (className in TARGET_CLASSES) {
            hookServiceClass(loader, className)
        }
    }

    private fun hookServiceClass(loader: ClassLoader?, className: String) {
        val cls = findClass(loader, className)
        if (cls == null) {
            ModuleRuntime.log("deep class missing $className")
            return
        }
        logTargetMethods(cls)
        var hooks = 0
        for (method in cls.declaredMethods) {
            val name = method.name
            val returnType = method.returnType
            val hooker: Class<out XposedInterface.Hooker>? = when {
                name == "getLastLocation" && returnType == Location::class.java ->
                    ServiceLocationReturnHooker::class.java
                name == "getCurrentLocation" ->
                    ServiceCurrentLocationHooker::class.java
                name == "requestLocationUpdates" || name == "registerLocationListener" ->
                    ServiceListenerHooker::class.java
                name == "removeUpdates" ->
                    ServiceTraceHooker::class.java
                name == "reportLocation" ->
                    ServiceReportLocationHooker::class.java
                // Server side of the UI command bridge: this is where the
                // process has the privileges to persist state for all hooks.
                name == "sendExtraCommand" && className == "com.android.server.location.LocationManagerService" ->
                    ServiceExtraCommandHooker::class.java
                name == "onTransact" && className == "android.location.ILocationManager\$Stub" ->
                    ServiceTraceHooker::class.java
                else -> null
            }
            if (hooker != null && hookMethodOnce(method, hooker)) {
                hooks++
            }
        }
        ModuleRuntime.log("deep hooks $className installed=$hooks")
    }

    private fun logTargetMethods(cls: Class<*>) {
        try {
            for (method in cls.declaredMethods) {
                val name = method.name
                if (name == "getLastLocation" || name == "requestLocationUpdates" || name == "getCurrentLocation"
                    || name == "registerLocationListener" || name == "removeUpdates"
                    || name == "reportLocation" || name == "onTransact"
                ) {
                    ModuleRuntime.log("deep method ${cls.name}#${ModuleRuntime.methodSignature(method)}")
                }
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("deep method discovery failed ${cls.name}", throwable)
        }
    }

    // ---- callbacks dispatched into non-public listener shapes ----

    fun dispatchLocationCallbacks(args: Array<Any?>?, location: Location) {
        if (args == null) {
            return
        }
        for (arg in args) {
            if (arg == null) {
                continue
            }
            if (arg is LocationListener) {
                try {
                    arg.onLocationChanged(location)
                } catch (throwable: Throwable) {
                    ModuleRuntime.log("service listener dispatch failed", throwable)
                }
                continue
            }
            if (tryDispatchRemoteLocation(arg, location)) {
                continue
            }
            tryDispatchRemoteLocationList(arg, location)
        }
    }

    private fun tryDispatchRemoteLocation(target: Any, location: Location): Boolean {
        for (name in arrayOf("onLocation", "onLocationChanged")) {
            if (tryInvoke(target, name, arrayOf(Location::class.java), arrayOf(location))) {
                return true
            }
        }
        return false
    }

    private fun tryDispatchRemoteLocationList(target: Any, location: Location): Boolean {
        val locationResult = makeLocationResult(location)
        if (locationResult != null && tryInvoke(
                target, "onLocationChanged",
                arrayOf(locationResult.javaClass), arrayOf(locationResult),
            )
        ) {
            return true
        }
        return tryInvoke(
            target, "onLocationChanged",
            arrayOf(MutableList::class.java), arrayOf(listOf(location)),
        )
    }

    fun replaceLocationResult(candidate: Any?, location: Location): Any? {
        if (candidate == null || candidate.javaClass.name != "android.location.LocationResult") {
            return candidate
        }
        return makeLocationResult(location) ?: candidate
    }

    private fun makeLocationResult(location: Location): Any? {
        val cls = findClass(DeepServiceHooks::class.java.classLoader, "android.location.LocationResult")
            ?: return null
        val locations = listOf(location)
        tryStaticInvoke(cls, "wrap", arrayOf(MutableList::class.java), arrayOf(locations))?.let { return it }
        tryStaticInvoke(cls, "create", arrayOf(MutableList::class.java), arrayOf(locations))?.let { return it }
        return try {
            val method = cls.getDeclaredMethod("create", Array<Location>::class.java)
            method.isAccessible = true
            method.invoke(null, arrayOf(location))
        } catch (_: Throwable) {
            null
        }
    }
}

class ServiceLocationReturnHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            HookSupport.logHit(callback.member, "service return")
            callback.returnAndSkip(SpoofState.makeLocation(HookSupport.providerFrom(callback.args)))
        }
    }
}

class ServiceCurrentLocationHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            if (!ModuleRuntime.active() || callback.throwable != null) {
                return
            }
            HookSupport.logHit(callback.member, "service current")
            DeepServiceHooks.dispatchLocationCallbacks(
                callback.args,
                SpoofState.makeLocation(HookSupport.providerFrom(callback.args)),
            )
        }
    }
}

class ServiceListenerHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            if (!ModuleRuntime.active() || callback.throwable != null) {
                return
            }
            HookSupport.logHit(callback.member, "service listener")
            DeepServiceHooks.dispatchLocationCallbacks(
                callback.args,
                SpoofState.makeLocation(HookSupport.providerFrom(callback.args)),
            )
        }
    }
}

class ServiceReportLocationHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            val args = callback.args ?: return
            var replaced = false
            val location = SpoofState.makeLocation(HookSupport.providerFrom(args))
            for (i in args.indices) {
                val arg = args[i]
                if (arg is Location) {
                    args[i] = location
                    replaced = true
                } else {
                    val result = DeepServiceHooks.replaceLocationResult(arg, location)
                    if (result !== arg) {
                        args[i] = result
                        replaced = true
                    }
                }
            }
            HookSupport.logHit(
                callback.member,
                if (replaced) "service report replaced" else "service report observed",
            )
        }
    }
}

class ServiceTraceHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            HookSupport.logHit(callback.member, "service trace uid=" + Binder.getCallingUid())
        }
    }
}

/**
 * Receives the UI's bridge commands inside system_server. Running here means
 * the state file write in Bridge.handle has system privileges, and skipping
 * the original implementation avoids it rejecting the unknown "ghostloc"
 * provider.
 */
class ServiceExtraCommandHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            val args = callback.args ?: return
            if (args.size < 2 || args[0] != dev.ghostloc.offline.core.ModuleRuntime.BRIDGE_PROVIDER) {
                return
            }
            val command = args[1]?.toString() ?: return
            var bundle: android.os.Bundle? = null
            for (arg in args) {
                if (arg is android.os.Bundle) {
                    bundle = arg
                }
            }
            dev.ghostloc.offline.core.Bridge.handle(command, bundle)
            callback.returnAndSkip(true)
        }
    }
}
