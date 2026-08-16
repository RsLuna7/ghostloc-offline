package dev.ghostloc.offline.hooks

import android.location.Location
import android.location.LocationManager
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.VENDOR_LOCATION_PACKAGES
import dev.ghostloc.offline.core.ModuleRuntime.findClass
import dev.ghostloc.offline.core.ModuleRuntime.hookMethodOnce
import dev.ghostloc.offline.core.ModuleRuntime.methodSignature
import dev.ghostloc.offline.core.SpoofState
import io.github.libxposed.api.XposedInterface

/**
 * Fused/vendor location processes. Allowlisted class names only; unknown ROMs
 * simply match nothing and log the miss.
 */
object VendorHooks {

    private val TARGET_CLASSES = listOf(
        "com.android.location.fused.FusedLocationProvider",
        "com.android.location.fused.FusedLocationProvider\$ChildLocationListener",
        "com.oplus.location.OplusLocationProvider",
        "com.xiaomi.location.fused.XiaomiFusedLocationProvider",
    )

    fun install(loader: ClassLoader?, packageName: String?) {
        if (!ModuleRuntime.vendorHooksEnabled() || packageName == null || packageName !in VENDOR_LOCATION_PACKAGES) {
            return
        }
        ModuleRuntime.log("vendor process observed package=$packageName")
        for (className in TARGET_CLASSES) {
            hookVendorLocationClass(loader, className)
        }
    }

    private fun hookVendorLocationClass(loader: ClassLoader?, className: String) {
        val cls = findClass(loader, className)
        if (cls == null) {
            ModuleRuntime.log("vendor class missing $className")
            return
        }
        var hooks = 0
        for (method in cls.declaredMethods) {
            val name = method.name
            val returnType = method.returnType
            val hooker: Class<out XposedInterface.Hooker>? = when {
                name == "chooseBestLocation" && returnType == Location::class.java ->
                    VendorLocationReturnHooker::class.java
                name == "onLocationChanged" ->
                    VendorLocationArgHooker::class.java
                name == "reportBestLocationLocked" || name == "onSetRequest"
                    || name == "onFlush" || name == "start" || name == "stop" ->
                    VendorTraceHooker::class.java
                else -> null
            }
            if (hooker != null && hookMethodOnce(method, hooker)) {
                hooks++
                ModuleRuntime.log("vendor method ${cls.name}#${methodSignature(method)}")
            }
        }
        ModuleRuntime.log("vendor hooks $className installed=$hooks")
    }
}

class VendorLocationReturnHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            HookSupport.logHit(callback.member, "vendor location")
            callback.returnAndSkip(SpoofState.makeLocation(LocationManager.FUSED_PROVIDER))
        }
    }
}

class VendorLocationArgHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            val args = callback.args ?: return
            val location = SpoofState.makeLocation(LocationManager.FUSED_PROVIDER)
            var replaced = false
            for (i in args.indices) {
                if (args[i] is Location) {
                    args[i] = location
                    replaced = true
                }
            }
            if (replaced) {
                HookSupport.logHit(callback.member, "vendor callback")
            }
        }
    }
}

class VendorTraceHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            HookSupport.logHit(callback.member, "vendor trace")
        }
    }
}
