package dev.ghostloc.offline.hooks

import android.net.wifi.WifiInfo
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.findClass
import dev.ghostloc.offline.core.ModuleRuntime.hookMethodOnce
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
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
 * On Android 13+ the Wi-Fi stack lives in the WifiStack APEX with its own
 * classloader, so the class is resolved through
 * `SystemServerClassLoaderFactory.sLoadedPaths` (key `service-wifi.jar`);
 * `SystemServiceManager#loadClassFromLoader` is hooked as a fallback for
 * ROMs where the map is not populated yet when we run.
 */
object WifiServiceHooks {

    const val IMPL_CLASS = "com.android.server.wifi.WifiServiceImpl"

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
                hookMethodOnce(method, ServiceLoadWatcherHooker::class.java)
            }
        }
    }

    fun hookImpl(cls: Class<*>) {
        var hooks = 0
        for (method in cls.declaredMethods) {
            val hooker: Class<out XposedInterface.Hooker>? = when (method.name) {
                "getScanResults", "getMatchingScanResults" -> WifiScanResultsHooker::class.java
                "getConnectionInfo" -> WifiConnectionInfoHooker::class.java
                "startScan" -> WifiStartScanHooker::class.java
                "registerScanResultsCallback" -> WifiScanCallbackHooker::class.java
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

class WifiScanResultsHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            if (!ModuleRuntime.wifiShield() || callback.throwable != null) {
                return
            }
            val masked = WifiServiceHooks.emptyWrappedResult(callback.result)
            if (masked !== callback.result) {
                HookSupport.logHit(callback.member, "wifi scan masked")
                callback.result = masked
            }
        }
    }
}

class WifiConnectionInfoHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.wifiShield()) {
                return
            }
            val masked = makeMaskedWifiInfo()
            if (masked != null) {
                HookSupport.logHit(callback.member, "wifi info masked")
                callback.returnAndSkip(masked)
            }
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
}

class WifiStartScanHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                HookSupport.logHit(callback.member, "wifi startScan masked")
                callback.returnAndSkip(false)
            }
        }
    }
}

/** Suppresses `onScanResultsAvailable` delivery for every registered
 *  callback object (the callback binder arrives as a method argument). */
class WifiScanCallbackHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.wifiShield()) {
                return
            }
            for (arg in callback.args ?: return) {
                if (arg == null) {
                    continue
                }
                for (method in arg.javaClass.declaredMethods) {
                    if (method.name == "onScanResultsAvailable" && !Modifier.isAbstract(method.modifiers)) {
                        hookMethodOnce(method, WifiScanCallbackSuppressHooker::class.java)
                    }
                }
            }
        }
    }
}

class WifiScanCallbackSuppressHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip(null)
            }
        }
    }
}

class ServiceLoadWatcherHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            val args = callback.args ?: return
            val name = args.getOrNull(0) as? String ?: return
            if (name == "com.android.server.wifi.WifiService") {
                val loader = args.getOrNull(1) as? ClassLoader ?: return
                val impl = findClass(loader, WifiServiceHooks.IMPL_CLASS)
                if (impl != null) {
                    WifiServiceHooks.hookImpl(impl)
                }
            }
        }
    }
}
