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
            val hooker: XposedInterface.Hooker? = when {
                name == "getLastLocation" && returnType == Location::class.java ->
                    ServiceLocationReturnHooker
                name == "getCurrentLocation" ->
                    ServiceCurrentLocationHooker
                name == "requestLocationUpdates" || name == "registerLocationListener" ->
                    ServiceListenerHooker
                name == "removeUpdates" ->
                    ServiceTraceHooker
                name == "reportLocation" ->
                    ServiceReportLocationHooker
                // Passive provider re-broadcast: every other provider's fix is
                // offered to passive listeners through this entry point.
                name == "updateLocation" && className == "com.android.server.location.provider.PassiveLocationProvider" ->
                    ServiceReportLocationHooker
                name == "isProviderEnabled" || name == "isProviderEnabledForUser" ->
                    ServiceProviderEnabledHooker
                name == "registerGnssStatusCallback" ->
                    ServiceTrueHooker
                name == "requestListenerFlush" ->
                    ServiceRejectHooker
                name == "addGnssMeasurementsListener" || name == "addGnssAntennaInfoListener" ->
                    ServiceRejectHooker
                name in TEST_PROVIDER_METHODS ->
                    ServiceRejectHooker
                name == "setExtraLocationControllerPackageEnabled" ->
                    ServiceDisableExtraControllerHooker
                // Server side of the UI command bridge: this is where the
                // process has the privileges to persist state for all hooks.
                name == "sendExtraCommand" && className == "com.android.server.location.LocationManagerService" ->
                    ServiceExtraCommandHooker
                name == "onTransact" && className == "android.location.ILocationManager\$Stub" ->
                    ServiceTraceHooker
                // Push channel for cell-info listeners: every registered app
                // receives these broadcasts, so the list must be synthetic too.
                className == "com.android.server.TelephonyRegistry" &&
                    (name == "notifyCellInfo" || name == "notifyCellInfoForSubscriber") ->
                    TelephonyCellNotifyHooker
                // Same push channel for the deprecated bundle-shaped cell
                // location (PhoneStateListener.LISTEN_CELL_LOCATION).
                className == "com.android.server.TelephonyRegistry" &&
                    (name == "notifyCellLocation" || name == "notifyCellLocationForSubscriber") ->
                    TelephonyCellLocationNotifyHooker
                // Wrap registered listener objects so their
                // onCellLocationChanged delivery is rewritten too.
                className == "com.android.server.TelephonyRegistry" &&
                    (name == "listen" || name == "listenWithEventList") ->
                    TelephonyListenHooker
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

object ServiceLocationReturnHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        HookSupport.logHit(chain.executable, "service return")
        return SpoofState.makeLocation(HookSupport.providerFrom(chain.args))
    }
}

object ServiceCurrentLocationHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        if (!ModuleRuntime.active()) {
            return result
        }
        HookSupport.logHit(chain.executable, "service current")
        DeepServiceHooks.dispatchLocationCallbacks(
            HookSupport.argArray(chain),
            SpoofState.makeLocation(HookSupport.providerFrom(chain.args)),
        )
        return result
    }
}

object ServiceListenerHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        if (!ModuleRuntime.active()) {
            return result
        }
        HookSupport.logHit(chain.executable, "service listener")
        DeepServiceHooks.dispatchLocationCallbacks(
            HookSupport.argArray(chain),
            SpoofState.makeLocation(HookSupport.providerFrom(chain.args)),
        )
        return result
    }
}

object ServiceReportLocationHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        var replaced = false
        val location = SpoofState.makeLocation(HookSupport.providerFrom(chain.args))
        val newArgs = chain.args.toTypedArray()
        for (i in newArgs.indices) {
            val arg = newArgs[i]
            if (arg is Location) {
                newArgs[i] = location
                replaced = true
            } else {
                val result = DeepServiceHooks.replaceLocationResult(arg, location)
                if (result !== arg) {
                    newArgs[i] = result
                    replaced = true
                }
            }
        }
        HookSupport.logHit(
            chain.executable,
            if (replaced) "service report replaced" else "service report observed",
        )
        return if (replaced) chain.proceed(newArgs) else chain.proceed()
    }
}

object ServiceRejectHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        HookSupport.logHit(chain.executable, "service rejected")
        return HookSupport.skipWithNeutralResult(chain)
    }
}

object ServiceTrueHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        HookSupport.logHit(chain.executable, "service forced true")
        return true
    }
}

object ServiceProviderEnabledHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        val provider = chain.args.firstOrNull { it is String } as? String ?: return chain.proceed()
        if (provider == LocationManager.GPS_PROVIDER || provider == LocationManager.NETWORK_PROVIDER) {
            HookSupport.logHit(chain.executable, "provider enabled forced")
            return true
        }
        return chain.proceed()
    }
}

object ServiceDisableExtraControllerHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        val args = chain.args
        if (args.isNotEmpty() && args[0] is Boolean) {
            val newArgs = args.toTypedArray()
            newArgs[0] = false
            HookSupport.logHit(chain.executable, "extra controller disabled")
            return chain.proceed(newArgs)
        }
        return chain.proceed()
    }
}

object ServiceTraceHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        HookSupport.logHit(chain.executable, "service trace uid=" + Binder.getCallingUid())
        return chain.proceed()
    }
}

/** Replaces the cell list pushed through TelephonyRegistry broadcasts. */
object TelephonyCellNotifyHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.cellShield()) {
            return chain.proceed()
        }
        val cells = dev.ghostloc.offline.synth.CellSynth.makeCellInfoList()
        var replaced = false
        val newArgs = chain.args.toTypedArray()
        for (i in newArgs.indices) {
            if (newArgs[i] is List<*>) {
                newArgs[i] = cells
                replaced = true
            }
        }
        if (replaced) {
            HookSupport.logHit(chain.executable, "telephony cell notify replaced")
            return chain.proceed(newArgs)
        }
        return chain.proceed()
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
object TelephonyCellLocationNotifyHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.cellShield()) {
            return chain.proceed()
        }
        var replaced = false
        val newArgs = chain.args.toTypedArray()
        for (i in newArgs.indices) {
            val replacement: Any? = when (newArgs[i]) {
                is android.os.Bundle -> dev.ghostloc.offline.synth.CellSynth.makeCellLocationBundle()
                is android.telephony.CellIdentity ->
                    dev.ghostloc.offline.synth.CellSynth.currentLteCellIdentity()
                else -> null
            }
            if (replacement != null) {
                newArgs[i] = replacement
                replaced = true
            }
        }
        if (replaced) {
            HookSupport.logHit(chain.executable, "telephony cell location push replaced")
            return chain.proceed(newArgs)
        }
        return chain.proceed()
    }
}

/**
 * TelephonyRegistry#listen / #listenWithEventList: wraps every registered
 * listener by hooking its `onCellLocationChanged` delivery method, the port of
 * the original's I3 wrapper. Hooking the binder-proxy method in
 * system_server rewrites the payload before it is marshalled to the app.
 */
object TelephonyListenHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.cellShield()) {
            val method = chain.executable as? Method
            val params = method?.parameterTypes
            val args = chain.args
            if (params != null) {
                for (i in params.indices) {
                    if (params[i].simpleName != "IPhoneStateListener") {
                        continue
                    }
                    val listener = args.getOrNull(i) ?: continue
                    wrapListener(listener)
                }
            }
        }
        return chain.proceed()
    }

    private fun wrapListener(listener: Any) {
        var hooks = 0
        for (declared in listener.javaClass.declaredMethods) {
            if (declared.name != "onCellLocationChanged" || declared.parameterCount != 1) {
                continue
            }
            if (ModuleRuntime.hookMethodOnce(declared, CellLocationListenerHooker)) {
                hooks++
            }
        }
        if (hooks > 0) {
            ModuleRuntime.log("cell location listener wrapped ${listener.javaClass.name} hooks=$hooks")
        }
    }
}

/** Delivery-side rewrite installed by [TelephonyListenHooker]. */
object CellLocationListenerHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.cellShield()) {
            return chain.proceed()
        }
        val args = chain.args
        val payload = args.firstOrNull() ?: return chain.proceed()
        val replacement: Any? = when (payload) {
            is android.os.Bundle -> dev.ghostloc.offline.synth.CellSynth.makeCellLocationBundle()
            is android.telephony.CellIdentity ->
                dev.ghostloc.offline.synth.CellSynth.currentLteCellIdentity()
            else -> null
        }
        if (replacement != null) {
            val newArgs = args.toTypedArray()
            newArgs[0] = replacement
            HookSupport.logHit(chain.executable, "cell location listener delivery replaced")
            return chain.proceed(newArgs)
        }
        return chain.proceed()
    }
}

/**
 * Receives the UI's bridge commands inside system_server. Running here means
 * the state file write in Bridge.handle has system privileges, and skipping
 * the original implementation avoids it rejecting the unknown "ghostloc"
 * provider. Deliberately not gated on [ModuleRuntime.active]: the bridge also
 * carries the "stop" command that turns spoofing off.
 */
object ServiceExtraCommandHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val args = chain.args
        if (args.size < 2 || args[0] != dev.ghostloc.offline.core.ModuleRuntime.BRIDGE_PROVIDER) {
            return chain.proceed()
        }
        val command = args[1]?.toString() ?: return chain.proceed()
        var bundle: android.os.Bundle? = null
        for (arg in args) {
            if (arg is android.os.Bundle) {
                bundle = arg
            }
        }
        dev.ghostloc.offline.core.Bridge.handle(command, bundle)
        return true
    }
}
