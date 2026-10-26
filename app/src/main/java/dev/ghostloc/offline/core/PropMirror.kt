package dev.ghostloc.offline.core

import android.util.Log

/**
 * Live state mirror in `persist.ghostloc.*` system properties, the port of the
 * original's cross-process channel. The state file in /data/misc/ghostloc is
 * only readable by system_server; these properties are readable everywhere, so
 * scoped processes (com.android.phone, fused vendor, apps the user added to
 * scope) observe config changes the moment the bridge writes them, without
 * waiting for a process restart.
 *
 * Writes go through reflection on the hidden SystemProperties class and are
 * only expected to succeed in system_server (same privilege the original
 * relied on); reads are attempted everywhere and silently fall back when the
 * property is unset. Property names must stay within the 32-char limit, so a
 * few long preference keys are mapped to short suffixes.
 */
object PropMirror {

    private const val TAG = "GhostLocProp"
    private const val PREFIX = "persist.ghostloc."

    /** Preference key -> property suffix; hot scalars only, values are the same strings StateStore persists. */
    private val PROP_KEYS: Map<String, String> = linkedMapOf(
        Prefs.ACTIVE to "active",
        Prefs.LAT to "lat",
        Prefs.LON to "lon",
        Prefs.SPEED to "speed",
        Prefs.ALTITUDE to "altitude",
        Prefs.ACCURACY to "accuracy",
        Prefs.BEARING to "bearing",
        Prefs.WIFI_SHIELD to "wifi_shield",
        Prefs.CELL_SHIELD to "cell_shield",
        Prefs.MOCK_SHIELD to "mock_shield",
        Prefs.MOVEMENT to "movement",
        Prefs.STARTED_AT to "started_at",
        Prefs.CELL_ENABLE_NR to "nr",
        Prefs.NMEA_ENABLED to "nmea",
        Prefs.AGPS_ENABLED to "agps",
        // "vendor_hooks_enabled" (38 chars with prefix) exceeds the name limit.
        Prefs.VENDOR_HOOKS_ENABLED to "vendor",
        Prefs.ROUTE_PAUSED to "route_paused",
        Prefs.ROUTE_PAUSE_ELAPSED_MS to "pause_ms",
    )

    private var setMethod: java.lang.reflect.Method? = null
    private var getMethod: java.lang.reflect.Method? = null
    private var setFailures = 0

    @Volatile
    private var writesDisabled = false

    fun propName(prefKey: String): String? = PROP_KEYS[prefKey]?.let { PREFIX + it }

    val propKeys: Set<String> get() = PROP_KEYS.keys

    /**
     * Mirrors the changed keys after a successful state-file write. On
     * SELinux-hardened ROMs (Android 15 OnePlus: system_server may not set
     * default_prop) every set is denied; after a few failures the write side
     * gives up and the bridge-pull channel carries live state instead.
     */
    fun sync(mutations: Map<String, String>) {
        if (writesDisabled) {
            return
        }
        for ((key, value) in mutations) {
            val prop = PROP_KEYS[key] ?: continue
            if (value.length > 91) {
                continue
            }
            set(PREFIX + prop, value)
        }
    }

    fun set(name: String, value: String) {
        if (writesDisabled) {
            return
        }
        try {
            val method = setMethod ?: run {
                val resolved = Class.forName("android.os.SystemProperties")
                    .getMethod("set", String::class.java, String::class.java)
                setMethod = resolved
                resolved
            }
            method.invoke(null, name, value)
            setFailures = 0
        } catch (throwable: Throwable) {
            val cause = throwable.cause?.javaClass?.simpleName ?: throwable.javaClass.simpleName
            setFailures++
            if (setFailures == 1) {
                Log.w(TAG, "set denied ($cause) — property mirror unavailable on this ROM")
            }
            if (setFailures >= 3) {
                writesDisabled = true
                Log.w(TAG, "property mirror writes disabled after $setFailures denials")
            }
        }
    }

    /** Current mirrored value, or null when unset / unreadable. */
    fun get(name: String): String? {
        return try {
            val method = getMethod ?: run {
                val resolved = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                getMethod = resolved
                resolved
            }
            val value = method.invoke(null, name, "") as String
            if (value.isNotEmpty()) value else null
        } catch (_: Throwable) {
            null
        }
    }

    fun prefValue(prefKey: String): String? = propName(prefKey)?.let { get(it) }

    fun hasPref(prefKey: String): Boolean = prefValue(prefKey) != null
}
