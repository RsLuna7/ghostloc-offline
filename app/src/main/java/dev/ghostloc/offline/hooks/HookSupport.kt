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

    fun args(callback: XposedInterface.BeforeHookCallback): Array<Any?>? = callback.args

    fun args(callback: XposedInterface.AfterHookCallback): Array<Any?>? = callback.args
}
