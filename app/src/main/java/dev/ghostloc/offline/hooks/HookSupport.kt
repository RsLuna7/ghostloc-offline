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

    fun providerFrom(args: List<Any?>?): String {
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

    fun isKnownProviderArg(args: List<Any?>?): Boolean {
        if (args == null) {
            return false
        }
        return args.any { it is String && it in KNOWN_PROVIDERS }
    }

    fun isCancellationSignalSet(args: List<Any?>?): Boolean {
        if (args == null) {
            return false
        }
        return args.any { it is CancellationSignal && it.isCanceled }
    }

    fun hasMockLocationKey(args: List<Any?>?): Boolean {
        if (args == null) {
            return false
        }
        return args.any { it == "mock_location" }
    }

    fun logHit(member: Any?, prefix: String) {
        ModuleRuntime.logHit(member, prefix)
    }

    /**
     * Neutral return value matching the hooked signature: `false` for
     * boolean, `null` for void/reference. Avoids unbox NPEs that a blanket
     * `null` would cause on boolean hooks. Returning it from [intercept]
     * skips the original method, like the old `returnAndSkip`.
     */
    fun skipWithNeutralResult(chain: XposedInterface.Chain): Any? {
        val method = chain.executable as? java.lang.reflect.Method
        return when (method?.returnType) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
            java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
            else -> null
        }
    }

    /** Snapshot of the immutable chain arguments as a mutable array. */
    fun argArray(chain: XposedInterface.Chain): Array<Any?> = chain.args.toTypedArray()
}
