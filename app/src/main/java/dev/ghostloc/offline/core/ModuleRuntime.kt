package dev.ghostloc.offline.core

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Arrays
import java.util.Collections
import java.util.HashSet
import java.util.Locale

/**
 * Process-wide runtime shared by every hooker: the framework bridge, remote
 * preferences, hook deduplication, diagnostics, and the small reflection
 * toolkit the synth layer builds on. Everything here must tolerate being
 * loaded in arbitrary hooked processes.
 */
object ModuleRuntime {
    const val TAG = "GhostLocModule"
    const val SELF_PACKAGE = "dev.ghostloc.offline"
    const val BRIDGE_PROVIDER = "ghostloc"
    const val FAKED_EXTRA = "ghostloc_faked"
    const val DIAGNOSTIC_LIMIT = 12

    val VENDOR_LOCATION_PACKAGES: Set<String> = Collections.unmodifiableSet(
        HashSet(Arrays.asList(
            "com.android.location.fused",
            "com.oplus.location",
            "com.xiaomi.location.fused",
        ))
    )

    private val installedHooks = Collections.synchronizedSet(HashSet<String>())
    private val loggedHits = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    var bridge: XposedInterface? = null

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    fun init(bridge: XposedInterface): SharedPreferences {
        this.bridge = bridge
        val prefs = bridge.getRemotePreferences(Prefs.GROUP)
        remotePrefs = prefs
        return prefs
    }

    /**
     * Effective settings for the hook layer: the live [StateStore] overlay
     * (written by system_server on every UI sync) merged over the boot-time
     * remote preferences. The returned view is read-only; writes go through
     * [Bridge].
     */
    fun prefs(): SharedPreferences? {
        val remote = remotePrefs ?: remotePrefsOrNull() ?: return null
        return MergedPrefs(remote)
    }

    private fun remotePrefsOrNull(): SharedPreferences? {
        val bridge = bridge ?: return null
        val prefs = bridge.getRemotePreferences(Prefs.GROUP)
        remotePrefs = prefs
        return prefs
    }

    fun active(): Boolean = prefs()?.getBoolean(Prefs.ACTIVE, false) ?: false

    fun currentLat(): Double = Double.fromBits(
        prefs()?.getLong(Prefs.LAT, Prefs.DEFAULT_LAT.toRawBits()) ?: Prefs.DEFAULT_LAT.toRawBits()
    )

    fun currentLon(): Double = Double.fromBits(
        prefs()?.getLong(Prefs.LON, Prefs.DEFAULT_LON.toRawBits()) ?: Prefs.DEFAULT_LON.toRawBits()
    )

    fun wifiShield(): Boolean = prefs()?.getBoolean(Prefs.WIFI_SHIELD, true) ?: true

    fun cellShield(): Boolean = prefs()?.getBoolean(Prefs.CELL_SHIELD, true) ?: true

    fun mockShield(): Boolean = prefs()?.getBoolean(Prefs.MOCK_SHIELD, true) ?: true

    fun vendorHooksEnabled(): Boolean = prefs()?.getBoolean(Prefs.VENDOR_HOOKS_ENABLED, true) ?: true

    fun log(message: String) {
        Log.i(TAG, message)
        val current = bridge ?: return
        try {
            current.log("$TAG: $message")
        } catch (_: Throwable) {
        }
    }

    fun log(message: String, throwable: Throwable) {
        recordError(message + " " + throwable.javaClass.simpleName)
        Log.i(TAG, message, throwable)
        val current = bridge ?: return
        try {
            current.log("$TAG: $message", throwable)
        } catch (_: Throwable) {
        }
    }

    fun hookMethodOnce(method: Method, hooker: Class<out XposedInterface.Hooker>): Boolean {
        val key = method.declaringClass.name + "#" + methodSignature(method)
        if (!installedHooks.add(key)) {
            return false
        }
        return try {
            method.isAccessible = true
            bridge?.hook(method, hooker)
            log("deep hook installed $key")
            true
        } catch (throwable: Throwable) {
            installedHooks.remove(key)
            log("deep hook skipped $key", throwable)
            false
        }
    }

    fun logHit(member: Any?, prefix: String) {
        val key = prefix + " " + hookKey(member)
        if (loggedHits.add(key)) {
            recordHit(key)
            log(key)
        }
    }

    private fun hookKey(member: Any?): String {
        if (member is Method) {
            return member.declaringClass.name + "#" + methodSignature(member)
        }
        return member.toString()
    }

    fun methodSignature(method: Method): String {
        val builder = StringBuilder(method.name).append('(')
        val params = method.parameterTypes
        for (i in params.indices) {
            if (i > 0) {
                builder.append(',')
            }
            builder.append(simpleTypeName(params[i]))
        }
        return builder.append("):").append(simpleTypeName(method.returnType)).toString()
    }

    private fun simpleTypeName(type: Class<*>): String {
        if (type.isArray) {
            return simpleTypeName(type.componentType) + "[]"
        }
        return type.name
    }

    fun findClass(loader: ClassLoader?, className: String): Class<*>? {
        return try {
            Class.forName(className, false, loader)
        } catch (_: Throwable) {
            try {
                Class.forName(className)
            } catch (_: Throwable) {
                null
            }
        }
    }

    // ---- diagnostics ring (kept in remote prefs, capped at DIAGNOSTIC_LIMIT lines) ----

    fun recordProcess(processName: String?) {
        try {
            val current = remotePrefs ?: return
            current.edit().putString(Prefs.DIAG_LAST_PROCESS, stamp(processName)).apply()
        } catch (_: Throwable) {
        }
    }

    fun recordHit(message: String) {
        appendDiagnostic(Prefs.DIAG_RECENT_HITS, message)
    }

    fun recordError(message: String) {
        appendDiagnostic(Prefs.DIAG_RECENT_ERRORS, message)
    }

    private fun appendDiagnostic(key: String, message: String) {
        try {
            val current = remotePrefs ?: return
            val existing = current.getString(key, "")
            current.edit().putString(key, appendLimited(existing, stamp(message))).apply()
        } catch (_: Throwable) {
        }
    }

    private fun appendLimited(existing: String?, entry: String): String {
        val lines = ArrayList<String>()
        if (!existing.isNullOrBlank()) {
            lines.addAll(existing.split("\n".toRegex()))
        }
        lines.add(entry)
        while (lines.size > DIAGNOSTIC_LIMIT) {
            lines.removeAt(0)
        }
        return lines.joinToString("\n")
    }

    private fun stamp(message: String?): String {
        return String.format(Locale.US, "%1\$tH:%1\$tM:%1\$tS %2\$s", System.currentTimeMillis(), message)
    }

    // ---- defensive reflection toolkit (every call site assumes ROM differences) ----

    fun tryInvoke(target: Any, methodName: String, types: Array<Class<*>>, args: Array<Any?>): Boolean {
        return try {
            val method = resolveMethod(target.javaClass, methodName, types) ?: return false
            method.isAccessible = true
            method.invoke(target, *args)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun tryInstanceInvoke(target: Any, methodName: String, types: Array<Class<*>>, args: Array<Any?>): Any? {
        return try {
            val method = resolveMethod(target.javaClass, methodName, types) ?: return null
            method.isAccessible = true
            method.invoke(target, *args)
        } catch (_: Throwable) {
            null
        }
    }

    fun tryStaticInvoke(cls: Class<*>, methodName: String, types: Array<Class<*>>, args: Array<Any?>): Any? {
        return try {
            val method = resolveMethod(cls, methodName, types) ?: return null
            method.isAccessible = true
            method.invoke(null, *args)
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveMethod(cls: Class<*>, methodName: String, types: Array<out Class<*>>): Method? {
        var current: Class<*>? = cls
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, *types)
            } catch (_: NoSuchMethodException) {
            }
            current = current.superclass
        }
        return try {
            cls.getMethod(methodName, *types)
        } catch (_: Throwable) {
            null
        }
    }

    fun hasMethod(cls: Class<*>, methodName: String, vararg types: Class<*>): Boolean {
        return resolveMethod(cls, methodName, types) != null
    }

    fun <T : Any> newInstance(cls: Class<T>): T? {
        return try {
            val constructor = cls.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance()
        } catch (_: Throwable) {
            null
        }
    }

    fun setFieldIfPresent(target: Any, fieldName: String, value: Any?): Boolean {
        val field = resolveField(target.javaClass, fieldName) ?: return false
        return try {
            field.isAccessible = true
            field.set(target, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveField(cls: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = cls
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
            }
            current = current.superclass
        }
        return null
    }
}
