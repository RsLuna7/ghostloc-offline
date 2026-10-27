package dev.ghostloc.offline.hooks

import android.net.wifi.WifiInfo
import android.os.Binder
import android.os.Process
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.findClass
import dev.ghostloc.offline.core.ModuleRuntime.hookMethodOnce
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

/**
 * Server-side Wi-Fi masking inside system_server.
 *
 * The client-side [WifiHooks] only affect processes listed in the module
 * scope, so an out-of-scope app that runs its own Wi-Fi scan (QQ's Tencent
 * location SDK uploads scanned BSSIDs to a server-side database) still sees
 * the real radio environment and re-derives the true position even though
 * every LocationManager answer is spoofed. Masking `WifiServiceImpl` — the
 * binder implementation behind `WifiManager` — empties scan results for
 * every caller regardless of scope.
 *
 * System-uid callers (uid 1000: the Settings WLAN picker and the wifi
 * stack's own background scans) are exempted. Everything that consumes
 * Wi-Fi for positioning runs as an app uid and stays masked; masking the
 * owner's own surfaces only locked them out of Wi-Fi management and
 * degraded stack auto-scans.
 */
object WifiServiceHooks {

    const val IMPL_CLASS = "com.android.server.wifi.WifiServiceImpl"

    /** True when the caller is the system itself (Settings, wifi stack). */
    fun isSystemCaller(): Boolean = Binder.getCallingUid() == Process.SYSTEM_UID

    fun install(loader: ClassLoader?) {
        val impl = resolveImplClass(loader)
        if (impl == null) {
            ModuleRuntime.log("wifi service impl not found; watching service loads")
            hookServiceLoadWatcher(loader)
            return
        }
        hookImpl(impl)
    }

    private fun resolveImplClass(loader: ClassLoader?): Class<*>? {
        val factory = findClass(loader, "com.android.internal.os.SystemServerClassLoaderFactory")
        if (factory != null) {
            try {
                val field = factory.getDeclaredField("sLoadedPaths")
                field.isAccessible = true
                val paths = field.get(null)
                if (paths is Map<*, *>) {
                    for ((key, value) in paths) {
                        if (key is String && key.contains("service-wifi") && value is ClassLoader) {
                            val cls = findClass(value, IMPL_CLASS)
                            if (cls != null) {
                                return cls
                            }
                        }
                    }
                }
            } catch (throwable: Throwable) {
                ModuleRuntime.log("wifi classloader factory read failed", throwable)
            }
        }
        return findClass(loader, IMPL_CLASS)
    }

    /** Catches `com.android.server.wifi.WifiService` class loads when the
     *  sLoadedPaths map was not ready at install time. */
    private fun hookServiceLoadWatcher(loader: ClassLoader?) {
        val manager = findClass(loader, "com.android.server.SystemServiceManager") ?: return
        for (method in manager.declaredMethods) {
            if (method.name == "loadClassFromLoader") {
                hookMethodOnce(method, ServiceLoadWatcherHooker)
            }
        }
    }

    fun hookImpl(cls: Class<*>) {
        var hooks = 0
        for (method in cls.declaredMethods) {
            val hooker: XposedInterface.Hooker? = when (method.name) {
                "getScanResults", "getMatchingScanResults" -> WifiScanResultsHooker
                "getConnectionInfo" -> WifiConnectionInfoHooker
                "startScan" -> WifiStartScanHooker
                "isScanAlwaysAvailable" -> WifiScanAlwaysAvailableHooker
                "registerScanResultsCallback" -> WifiScanCallbackHooker
                else -> null
            }
            if (hooker != null && hookMethodOnce(method, hooker)) {
                hooks++
            }
        }
        ModuleRuntime.log("wifi service hooks installed=$hooks")
    }

    /** Wraps an empty list in the same wrapper class the original result uses
     *  (system services return `ParceledListSlice`, not plain lists). */
    fun emptyWrappedResult(original: Any?): Any? {
        if (original == null) {
            return original
        }
        if (original is List<*>) {
            return ArrayList<Any>()
        }
        if (original.javaClass.isArray) {
            return java.lang.reflect.Array.newInstance(original.javaClass.componentType, 0)
        }
        val name = original.javaClass.name
        if (name.contains("ParceledListSlice")) {
            try {
                val constructor = original.javaClass.getConstructor(List::class.java)
                constructor.isAccessible = true
                return constructor.newInstance(ArrayList<Any>())
            } catch (_: Throwable) {
            }
        }
        return original
    }
}

object WifiScanResultsHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        if (WifiServiceHooks.isSystemCaller() || !ModuleRuntime.wifiShield()) {
            return result
        }
        val masked = WifiServiceHooks.emptyWrappedResult(result)
        if (masked !== result) {
            HookSupport.logHit(chain.executable, "wifi scan masked")
            return masked
        }
        return result
    }
}

object WifiConnectionInfoHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (WifiServiceHooks.isSystemCaller() || !ModuleRuntime.wifiShield()) {
            return chain.proceed()
        }
        val masked = makeMaskedWifiInfo()
        if (masked != null) {
            HookSupport.logHit(chain.executable, "wifi info masked")
            return masked
        }
        return chain.proceed()
    }

    private fun makeMaskedWifiInfo(): WifiInfo? {
        return try {
            val constructor = WifiInfo::class.java.getDeclaredConstructor()
            constructor.isAccessible = true
            val info = constructor.newInstance()
            ModuleRuntime.tryInvoke(info, "setMacAddress", arrayOf(String::class.java), arrayOf("02:00:00:00:00:00"))
            ModuleRuntime.tryInvoke(info, "setBSSID", arrayOf(String::class.java), arrayOf("02:00:00:00:00:00"))
            info
        } catch (throwable: Throwable) {
            ModuleRuntime.log("masked WifiInfo construction failed", throwable)
            null
        }
    }
}

object WifiStartScanHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (WifiServiceHooks.isSystemCaller()) {
            return chain.proceed()
        }
        if (ModuleRuntime.wifiShield()) {
            HookSupport.logHit(chain.executable, "wifi startScan masked")
            return false
        }
        return chain.proceed()
    }
}

object WifiScanAlwaysAvailableHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (WifiServiceHooks.isSystemCaller()) {
            return chain.proceed()
        }
        if (ModuleRuntime.wifiShield()) {
            HookSupport.logHit(chain.executable, "wifi scan-always masked")
            return false
        }
        return chain.proceed()
    }
}

/** Suppresses `onScanResultsAvailable` delivery for every registered
 *  callback object (the callback binder arrives as a method argument). */
object WifiScanCallbackHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (WifiServiceHooks.isSystemCaller() || !ModuleRuntime.wifiShield()) {
            return chain.proceed()
        }
        for (arg in chain.args) {
            if (arg == null) {
                continue
            }
            for (method in arg.javaClass.declaredMethods) {
                if (method.name == "onScanResultsAvailable" && !Modifier.isAbstract(method.modifiers)) {
                    hookMethodOnce(method, WifiScanCallbackSuppressHooker)
                }
            }
        }
        return chain.proceed()
    }
}

object WifiScanCallbackSuppressHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (WifiServiceHooks.isSystemCaller() || !ModuleRuntime.wifiShield()) {
            return chain.proceed()
        }
        return null
    }
}

object ServiceLoadWatcherHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val args = chain.args
        val name = args.getOrNull(0) as? String ?: return result
        if (name == "com.android.server.wifi.WifiService") {
            val loader = args.getOrNull(1) as? ClassLoader ?: return result
            val impl = findClass(loader, WifiServiceHooks.IMPL_CLASS)
            if (impl != null) {
                WifiServiceHooks.hookImpl(impl)
            }
        }
        return result
    }
}
