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
            val hooker: XposedInterface.Hooker? = when {
                name == "chooseBestLocation" && returnType == Location::class.java ->
                    VendorLocationReturnHooker
                name == "onLocationChanged" ->
                    VendorLocationArgHooker
                name == "reportBestLocationLocked" || name == "onSetRequest"
                    || name == "onFlush" || name == "start" || name == "stop" ->
                    VendorTraceHooker
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

object VendorLocationReturnHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        HookSupport.logHit(chain.executable, "vendor location")
        return SpoofState.makeLocation(LocationManager.FUSED_PROVIDER)
    }
}

object VendorLocationArgHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        val location = SpoofState.makeLocation(LocationManager.FUSED_PROVIDER)
        var replaced = false
        val newArgs = chain.args.toTypedArray()
        for (i in newArgs.indices) {
            if (newArgs[i] is Location) {
                newArgs[i] = location
                replaced = true
            }
        }
        if (replaced) {
            HookSupport.logHit(chain.executable, "vendor callback")
            return chain.proceed(newArgs)
        }
        return chain.proceed()
    }
}

object VendorTraceHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        HookSupport.logHit(chain.executable, "vendor trace")
        return chain.proceed()
    }
}
