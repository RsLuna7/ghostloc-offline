package dev.ghostloc.offline.core

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock

/**
 * Live-state pull channel for processes that cannot read the state file
 * (com.android.phone, vendor processes, scoped apps) and get no property
 * mirror on SELinux-hardened ROMs.
 *
 * The system_server hook on `LocationManagerService#sendExtraCommand`
 * answers the "query" command with the full current state written back into
 * the inout Bundle, so a plain `sendExtraCommand("ghostloc", "query", …)`
 * from any hooked process returns live values without new permissions or
 * file access. Results are cached briefly: hook layers consult preferences
 * on every hit, and one binder round trip per window keeps that cheap.
 */
object LiveState {

    private const val TTL_MS = 1500L
    private const val MAX_FAILURES = 3

    /**
     * A dead bridge (module updated, system_server about to be bounced by the
     * watchdog) must not disable the channel forever: re-arm after this window
     * so a recovered hook layer is picked up without restarting the process.
     */
    private const val RETRY_BACKOFF_MS = 60_000L

    @Volatile
    private var cached: Map<String, String>? = null

    @Volatile
    private var cachedAt = 0L

    private var failures = 0

    @Volatile
    private var disabledUntil = 0L

    /** Current live overlay; empty while the channel is backing off. */
    fun props(): Map<String, String> {
        if (SystemClock.elapsedRealtime() < disabledUntil) {
            return emptyMap()
        }
        if (SystemClock.elapsedRealtime() - cachedAt < TTL_MS) {
            return cached ?: emptyMap()
        }
        return synchronized(this) {
            if (SystemClock.elapsedRealtime() < disabledUntil) {
                return emptyMap()
            }
            if (SystemClock.elapsedRealtime() - cachedAt < TTL_MS) {
                return cached ?: emptyMap()
            }
            when (val fresh = query()) {
                null -> {
                    failures++
                    if (failures >= MAX_FAILURES) {
                        disabledUntil = SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS
                        failures = 0
                        ModuleRuntime.log("live state channel backing off after $MAX_FAILURES failures")
                    }
                }
                else -> {
                    failures = 0
                    cached = fresh
                    cachedAt = SystemClock.elapsedRealtime()
                }
            }
            cached ?: emptyMap()
        }
    }

    /**
     * Null means "not answerable right now" (no application context yet, no
     * location service, binder error, or an empty writeback) and does not
     * necessarily indicate a broken channel, so only completed-but-empty
     * responses count toward disabling.
     */
    private fun query(): Map<String, String>? {
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (_: Throwable) {
            null
        } ?: return null
        val manager = try {
            context.getSystemService(LocationManager::class.java)
        } catch (_: Throwable) {
            null
        } ?: return null
        val bundle = Bundle()
        val delivered = try {
            manager.sendExtraCommand(ModuleRuntime.BRIDGE_PROVIDER, "query", bundle)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (!delivered) {
            return null
        }
        val values = HashMap<String, String>()
        for (key in bundle.keySet()) {
            val value = bundle.get(key)?.toString() ?: continue
            values[key] = value
        }
        return if (values.isEmpty()) null else values
    }
}
