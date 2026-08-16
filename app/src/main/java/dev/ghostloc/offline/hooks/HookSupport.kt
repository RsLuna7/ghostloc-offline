package dev.ghostloc.offline.hooks

import android.location.LocationManager
import android.os.CancellationSignal
import dev.ghostloc.offline.core.ModuleRuntime
import io.github.libxposed.api.XposedInterface

/** Argument-inspection helpers shared by the location hookers. */
object HookSupport {

    private val KNOWN_PROVIDERS = setOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        LocationManager.FUSED_PROVIDER,
    )

    fun providerFrom(args: Array<Any?>?): String {
        if (args != null) {
            for (arg in args) {
                if (arg is String && arg in KNOWN_PROVIDERS) {
                    return arg
                }
            }
            for (arg in args) {
                providerFromRequest(arg)?.let { return it }
            }
        }
        return LocationManager.GPS_PROVIDER
    }

    private fun providerFromRequest(candidate: Any?): String? {
        if (candidate == null) {
            return null
        }
        return try {
            val method = candidate.javaClass.getMethod("getProvider")
            val value = method.invoke(candidate)
            if (value is String && value.isNotEmpty()) value else null
        } catch (_: Throwable) {
            null
        }
    }

    fun isKnownProviderArg(args: Array<Any?>?): Boolean {
        if (args == null) {
            return false
        }
        return args.any { it is String && it in KNOWN_PROVIDERS }
    }

    fun isCancellationSignalSet(args: Array<Any?>?): Boolean {
        if (args == null) {
            return false
        }
        return args.any { it is CancellationSignal && it.isCanceled }
    }

    fun hasMockLocationKey(args: Array<Any?>?): Boolean {
        if (args == null) {
            return false
        }
        return args.any { it == "mock_location" }
    }

    fun logHit(member: Any?, prefix: String) {
        ModuleRuntime.logHit(member, prefix)
    }

    /**
     * Skip the original method with a return value that matches the
     * signature: `false` for boolean, `null` for void/reference. Avoids
     * unbox NPEs that a blanket `null` would cause on boolean hooks.
     */
    fun skipWithNeutralResult(callback: XposedInterface.BeforeHookCallback) {
        val method = callback.member as? java.lang.reflect.Method
        val result: Any? = when (method?.returnType) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
            java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
            else -> null
        }
        callback.returnAndSkip(result)
    }

    fun args(callback: XposedInterface.BeforeHookCallback): Array<Any?>? = callback.args

    fun args(callback: XposedInterface.AfterHookCallback): Array<Any?>? = callback.args
}
