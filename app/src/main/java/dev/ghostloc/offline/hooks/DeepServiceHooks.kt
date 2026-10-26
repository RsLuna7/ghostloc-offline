package dev.ghostloc.offline.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
        "com.android.server.location.provider.PassiveLocationProvider",
        "com.android.server.location.gnss.GnssManagerService",
        "com.android.server.location.gnss.hal.GnssNative",
        "com.android.server.TelephonyRegistry",
    )

    private val TEST_PROVIDER_METHODS = setOf(
        "addTestProvider",
        "removeTestProvider",
        "setTestProviderLocation",
        "setTestProviderEnabled",
        "setExtraLocationControllerPackage",
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
                // Passive provider re-broadcast: every other provider's fix is
                // offered to passive listeners through this entry point.
                name == "updateLocation" && className == "com.android.server.location.provider.PassiveLocationProvider" ->
                    ServiceReportLocationHooker::class.java
                name == "isProviderEnabled" || name == "isProviderEnabledForUser" ->
                    ServiceProviderEnabledHooker::class.java
                name == "registerGnssStatusCallback" ->
                    ServiceTrueHooker::class.java
                name == "requestListenerFlush" ->
                    ServiceRejectHooker::class.java
                name == "addGnssMeasurementsListener" || name == "addGnssAntennaInfoListener" ->
                    ServiceRejectHooker::class.java
                name in TEST_PROVIDER_METHODS ->
                    ServiceRejectHooker::class.java
                name == "setExtraLocationControllerPackageEnabled" ->
                    ServiceDisableExtraControllerHooker::class.java
                // Server side of the UI command bridge: this is where the
                // process has the privileges to persist state for all hooks.
                name == "sendExtraCommand" && className == "com.android.server.location.LocationManagerService" ->
                    ServiceExtraCommandHooker::class.java
                name == "onTransact" && className == "android.location.ILocationManager\$Stub" ->
                    ServiceTraceHooker::class.java
                // Push channel for cell-info listeners: every registered app
                // receives these broadcasts, so the list must be synthetic too.
                className == "com.android.server.TelephonyRegistry" &&
                    (name == "notifyCellInfo" || name == "notifyCellInfoForSubscriber") ->
                    TelephonyCellNotifyHooker::class.java
                // Same push channel for the deprecated bundle-shaped cell
                // location (PhoneStateListener.LISTEN_CELL_LOCATION).
                className == "com.android.server.TelephonyRegistry" &&
                    (name == "notifyCellLocation" || name == "notifyCellLocationForSubscriber") ->
                    TelephonyCellLocationNotifyHooker::class.java
                // Wrap registered listener objects so their
                // onCellLocationChanged delivery is rewritten too.
                className == "com.android.server.TelephonyRegistry" &&
                    (name == "listen" || name == "listenWithEventList") ->
                    TelephonyListenHooker::class.java
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
                    || name == "reportLocation" || name == "updateLocation" || name == "onTransact"
                    || name == "isProviderEnabled" || name == "isProviderEnabledForUser"
                    || name == "registerGnssStatusCallback"
                    || name == "addGnssMeasurementsListener" || name == "addGnssAntennaInfoListener"
                    || name == "addTestProvider" || name == "listen" || name == "listenWithEventList"
                    || name == "notifyCellLocation" || name == "notifyCellLocationForSubscriber"
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
            if (tryDispatchBinderLocationList(arg, location)) {
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

    /**
     * The service-side `getCurrentLocation` callbacks are binder proxies of
     * `ILocationListener`/`ILocationCallback`, whose delivery methods take the
     * payload plus an `IRemoteCallback` completion token — shapes the
     * single-argument attempts above never match, which is why
     * `getCurrentLocation` used to come back null while spoofing. The payload
     * itself is `List<Location>` for listeners but `LocationResult` for the
     * `ILocationCallback` shape `getCurrentLocation` uses.
     */
    private fun tryDispatchBinderLocationList(target: Any, location: Location): Boolean {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != "onLocationChanged" || method.parameterCount != 2) {
                    continue
                }
                val first = method.parameterTypes[0]
                val payload: Any? = when {
                    List::class.java.isAssignableFrom(first) -> listOf(location)
                    first.name == "android.location.LocationResult" ->
                        makeLocationResult(location) ?: continue
                    first == Location::class.java -> location
                    else -> continue
                }
                return try {
                    method.isAccessible = true
                    method.invoke(target, payload, null)
                    true
                } catch (throwable: Throwable) {
                    ModuleRuntime.log("binder list dispatch failed", throwable)
                    false
                }
            }
            current = current.superclass
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

    /**
     * Deterministic exercise of the cell-location push chain for device
     * verification: resolves the TelephonyRegistry service and invokes
     * `notifyCellLocationForSubscriber` with the current synthetic identity.
     * The before-hook replaces the argument (logging the hit) and the
     * original implementation delivers it to registered listeners, whose
     * wrapped delivery methods log their own hits. Only meaningful in
     * system_server; returns false everywhere else.
     */
    fun triggerCellLocationPush(): Boolean {
        fun trace(step: String) {
            ModuleRuntime.log("cell push trace $step")
        }
        return try {
            val smClass = findClass(
                DeepServiceHooks::class.java.classLoader, "android.os.ServiceManager",
            ) ?: run { trace("no ServiceManager"); return false }
            trace("sm ok")
            val binder = tryStaticInvoke(smClass, "getService", arrayOf(String::class.java), arrayOf("telephony.registry"))
                ?: run { trace("no binder"); return false }
            trace("binder ok")
            val stubClass = findClass(
                DeepServiceHooks::class.java.classLoader,
                "com.android.internal.telephony.ITelephonyRegistry\$Stub",
            ) ?: run { trace("no stub class"); return false }
            trace("stub ok")
            val registry = tryStaticInvoke(stubClass, "asInterface", arrayOf(android.os.IBinder::class.java), arrayOf(binder))
                ?: run { trace("no registry"); return false }
            trace("registry ok")
            val identity = dev.ghostloc.offline.synth.CellSynth.currentLteCellIdentity()
                ?: run { trace("no identity"); return false }
            trace("identity ok")
            if (tryInvoke(
                    registry, "notifyCellLocationForSubscriber",
                    arrayOf(java.lang.Integer.TYPE, android.telephony.CellIdentity::class.java),
                    arrayOf(0, identity),
                )
            ) {
                trace("delivered 2-arg")
                return true
            }
            trace("2-arg miss")
            if (tryInvoke(
                    registry, "notifyCellLocationForSubscriber",
                    arrayOf(
                        java.lang.Integer.TYPE,
                        android.telephony.CellIdentity::class.java,
                        java.lang.Boolean.TYPE,
                    ),
                    arrayOf(0, identity, false),
                )
            ) {
                trace("delivered 3-arg")
                return true
            }
            trace("3-arg miss")
            false
        } catch (throwable: Throwable) {
            ModuleRuntime.log("cell push failed " + throwable.javaClass.simpleName, throwable)
            false
        }
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

class ServiceRejectHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            HookSupport.logHit(callback.member, "service rejected")
            HookSupport.skipWithNeutralResult(callback)
        }
    }
}

class ServiceTrueHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            HookSupport.logHit(callback.member, "service forced true")
            callback.returnAndSkip(true)
        }
    }
}

class ServiceProviderEnabledHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            val provider = callback.args?.firstOrNull { it is String } as? String ?: return
            if (provider == LocationManager.GPS_PROVIDER || provider == LocationManager.NETWORK_PROVIDER) {
                HookSupport.logHit(callback.member, "provider enabled forced")
                callback.returnAndSkip(true)
            }
        }
    }
}

class ServiceDisableExtraControllerHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            val args = callback.args ?: return
            if (args.isNotEmpty() && args[0] is Boolean) {
                args[0] = false
                HookSupport.logHit(callback.member, "extra controller disabled")
            }
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

/** Replaces the cell list pushed through TelephonyRegistry broadcasts. */
class TelephonyCellNotifyHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.cellShield()) {
                return
            }
            val args = callback.args ?: return
            val cells = dev.ghostloc.offline.synth.CellSynth.makeCellInfoList()
            var replaced = false
            for (i in args.indices) {
                if (args[i] is List<*>) {
                    args[i] = cells
                    replaced = true
                }
            }
            if (replaced) {
                HookSupport.logHit(callback.member, "telephony cell notify replaced")
            }
        }
    }
}

/**
 * Replaces the deprecated push-shaped cell location delivered to legacy
 * `PhoneStateListener#onCellLocationChanged` registrants, the port of the
 * original's fake-CDMA-bundle behavior. On Android 15 the payload is a
 * `CellIdentity` (device log: `notifyCellLocationForSubscriber(int,
 * CellIdentity[, boolean])`); older ROMs pushed a Bundle — both shapes are
 * replaced with the matching synthetic value so push and pull stay
 * consistent.
 */
class TelephonyCellLocationNotifyHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.cellShield()) {
                return
            }
            val args = callback.args ?: return
            var replaced = false
            for (i in args.indices) {
                val replacement: Any? = when (args[i]) {
                    is android.os.Bundle -> dev.ghostloc.offline.synth.CellSynth.makeCellLocationBundle()
                    is android.telephony.CellIdentity ->
                        dev.ghostloc.offline.synth.CellSynth.currentLteCellIdentity()
                    else -> null
                }
                if (replacement != null) {
                    args[i] = replacement
                    replaced = true
                }
            }
            if (replaced) {
                HookSupport.logHit(callback.member, "telephony cell location push replaced")
            }
        }
    }
}

/**
 * TelephonyRegistry#listen / #listenWithEventList: wraps every registered
 * listener by hooking its `onCellLocationChanged` delivery method, the port of
 * the original's I3 wrapper. Hooking the binder-proxy method in
 * system_server rewrites the payload before it is marshalled to the app.
 */
class TelephonyListenHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.cellShield()) {
                return
            }
            val method = callback.member as? java.lang.reflect.Method ?: return
            val params = method.parameterTypes
            val args = callback.args ?: return
            for (i in params.indices) {
                if (params[i].simpleName != "IPhoneStateListener") {
                    continue
                }
                val listener = args.getOrNull(i) ?: continue
                wrapListener(listener)
            }
        }

        private fun wrapListener(listener: Any) {
            var hooks = 0
            for (declared in listener.javaClass.declaredMethods) {
                if (declared.name != "onCellLocationChanged" || declared.parameterCount != 1) {
                    continue
                }
                if (ModuleRuntime.hookMethodOnce(declared, CellLocationListenerHooker::class.java)) {
                    hooks++
                }
            }
            if (hooks > 0) {
                ModuleRuntime.log("cell location listener wrapped ${listener.javaClass.name} hooks=$hooks")
            }
        }
    }
}

/** Delivery-side rewrite installed by [TelephonyListenHooker]. */
class CellLocationListenerHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.cellShield()) {
                return
            }
            val args = callback.args ?: return
            val payload = args.firstOrNull() ?: return
            val replacement: Any? = when (payload) {
                is android.os.Bundle -> dev.ghostloc.offline.synth.CellSynth.makeCellLocationBundle()
                is android.telephony.CellIdentity ->
                    dev.ghostloc.offline.synth.CellSynth.currentLteCellIdentity()
                else -> null
            }
            if (replacement != null) {
                args[0] = replacement
                HookSupport.logHit(callback.member, "cell location listener delivery replaced")
            }
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
