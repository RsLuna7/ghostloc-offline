package dev.ghostloc.offline.hooks

import android.location.Location
import android.location.LocationManager
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.findClass
import dev.ghostloc.offline.core.ModuleRuntime.hookMethodOnce
import dev.ghostloc.offline.core.ModuleRuntime.tryInvoke
import dev.ghostloc.offline.core.SpoofState
import io.github.libxposed.api.XposedInterface

/**
 * Third-party location SDK counter-measures, the port of the original's
 * `Jo.l` hook group. These run inside scoped app processes (the user adds the
 * target app, e.g. QQ, to the module scope) as a second line of defense behind
 * the server-side masking: when an embedded SDK resolves its location through
 * its own native/service channel, the answer is rewritten here.
 *
 * Targets are looked up eagerly by name through the host classloader, so an
 * app that does not embed an SDK simply matches nothing.
 */
object SdkHooks {

    private const val TENCENT_NLP = "com.tencent.geolocation.nlp.TencentNLPManager"
    private const val AMAP_NLP = "com.amap.android.location.NetworkLocationManager"
    private const val BAIDU_CLIENT = "com.baidu.location.LocationClient"

    fun install(loader: ClassLoader?) {
        val tencent = hookTencentNlp(loader)
        val amap = hookAmapNlp(loader)
        val baidu = hookBaiduClient(loader)
        if (tencent + amap + baidu > 0) {
            ModuleRuntime.log("sdk hooks installed tencent=$tencent amap=$amap baidu=$baidu")
        }
    }

    /**
     * Tencent: blind-hook every declared method that hands a Location in or
     * out, like the original's P7.k helper.
     */
    private fun hookTencentNlp(loader: ClassLoader?): Int {
        val cls = findClass(loader, TENCENT_NLP) ?: return 0
        var hooks = 0
        for (method in cls.declaredMethods) {
            val hooker: Class<out XposedInterface.Hooker>? = when {
                method.returnType == Location::class.java -> SdkLocationReturnHooker::class.java
                method.parameterTypes.any { it == Location::class.java } ->
                    SdkLocationArgHooker::class.java
                else -> null
            }
            if (hooker != null && ModuleRuntime.hookMethodOnce(method, hooker)) {
                hooks++
            }
        }
        ModuleRuntime.log("sdk tencent hooks=$hooks on ${cls.name}")
        return hooks
    }

    /**
     * AMap network location: deny the extra-command round trip and the
     * off-location toggle. The original forced `false`/`null` returns; here
     * both are neutral-skipped per signature and only while spoofing is on,
     * so an inactive module never degrades the SDK.
     */
    private fun hookAmapNlp(loader: ClassLoader?): Int {
        val cls = findClass(loader, AMAP_NLP) ?: return 0
        var hooks = 0
        for (method in cls.declaredMethods) {
            if (method.name == "onSendExtraCommand" || method.name == "updateOffLocEnable") {
                if (ModuleRuntime.hookMethodOnce(method, SdkAmapRejectHooker::class.java)) {
                    hooks++
                }
            }
        }
        ModuleRuntime.log("sdk amap hooks=$hooks on ${cls.name}")
        return hooks
    }

    /**
     * Baidu: rewrite the BDLocation produced by the network request in place
     * (coordinates plus building/address scrub), like the original's Tn(0).
     */
    private fun hookBaiduClient(loader: ClassLoader?): Int {
        val cls = findClass(loader, BAIDU_CLIENT) ?: return 0
        var hooks = 0
        for (method in cls.declaredMethods) {
            if (method.name == "requestNLPNormal") {
                if (ModuleRuntime.hookMethodOnce(method, SdkBaiduLocationHooker::class.java)) {
                    hooks++
                }
            }
        }
        ModuleRuntime.log("sdk baidu hooks=$hooks on ${cls.name}")
        return hooks
    }
}

/** Rewrites a Location returned by the SDK's own resolution. */
class SdkLocationReturnHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            if (!ModuleRuntime.active() || callback.throwable != null) {
                return
            }
            val result = callback.result
            if (result is Location) {
                HookSupport.logHit(callback.member, "sdk location return")
                callback.result = BasicLocationHooks.remap(result)
            }
        }
    }
}

/** Rewrites Location arguments the SDK passes into its own callbacks. */
class SdkLocationArgHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            val args = callback.args ?: return
            var replaced = false
            for (i in args.indices) {
                val arg = args[i]
                if (arg is Location) {
                    args[i] = BasicLocationHooks.remap(arg)
                    replaced = true
                }
            }
            if (replaced) {
                HookSupport.logHit(callback.member, "sdk location arg")
            }
        }
    }
}

/** Neutral-skips the AMap NLP command/toggle entry points while spoofing. */
class SdkAmapRejectHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            HookSupport.logHit(callback.member, "sdk amap rejected")
            HookSupport.skipWithNeutralResult(callback)
        }
    }
}

/**
 * Rewrites the Baidu BDLocation result in place: spoofed coordinates plus
 * cleared building/address strings (they would still describe the real
 * surroundings).
 */
class SdkBaiduLocationHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            if (!ModuleRuntime.active() || callback.throwable != null) {
                return
            }
            val result = callback.result ?: return
            val location = SpoofState.makeLocation(LocationManager.NETWORK_PROVIDER)
            val doubleType = Double::class.javaPrimitiveType!!
            var changed = tryInvoke(
                result, "setLatitude", arrayOf(doubleType), arrayOf(location.latitude),
            )
            changed = tryInvoke(
                result, "setLongitude", arrayOf(doubleType), arrayOf(location.longitude),
            ) || changed
            tryInvoke(result, "setBuildingID", arrayOf(String::class.java), arrayOf(""))
            tryInvoke(result, "setAddrStr", arrayOf(String::class.java), arrayOf(""))
            if (changed) {
                HookSupport.logHit(callback.member, "sdk baidu location")
            }
        }
    }
}
