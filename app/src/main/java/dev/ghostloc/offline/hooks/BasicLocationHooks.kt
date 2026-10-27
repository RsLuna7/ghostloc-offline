package dev.ghostloc.offline.hooks

import android.location.Location
import android.os.Bundle
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.findClass
import dev.ghostloc.offline.core.ModuleRuntime.hookMethodOnce
import dev.ghostloc.offline.core.SpoofState
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Serialization-level fallback: any [Location] that leaves a process over
 * Binder is rewritten even if an earlier service path was missed.
 *
 * Installed in system_server only. The public `Location` class lives on the
 * boot classpath, so hooking `writeToParcel` / `set` there covers every
 * outgoing parcel from the location service.
 */
object BasicLocationHooks {

    const val FAKED_EXTRA = ModuleRuntime.FAKED_EXTRA

    fun install(loader: ClassLoader?) {
        hookLocationClass()
        hookLocationResultClass(loader)
    }

    private fun hookLocationClass() {
        var hooks = 0
        for (method in Location::class.java.declaredMethods) {
            if (Modifier.isAbstract(method.modifiers)) {
                continue
            }
            val hooker: XposedInterface.Hooker? = when (method.name) {
                "writeToParcel" -> LocationParcelHooker
                "set" -> LocationSetHooker
                else -> null
            }
            if (hooker != null && hookMethodOnce(method, hooker)) {
                hooks++
            }
        }
        ModuleRuntime.log("basic location hooks installed=$hooks")
    }

    private fun hookLocationResultClass(loader: ClassLoader?) {
        val cls = findClass(loader, "android.location.LocationResult")
        if (cls == null) {
            ModuleRuntime.log("LocationResult class missing")
            return
        }
        var hooks = 0
        for (method in cls.declaredMethods) {
            if (Modifier.isAbstract(method.modifiers) || method.isSynthetic) {
                continue
            }
            val hooker: XposedInterface.Hooker? = when {
                method.name == "asList" -> LocationResultAsListHooker
                method.name == "writeToParcel" -> LocationResultParcelHooker
                method.returnType == Location::class.java ||
                    method.parameterTypes.any { it == Location::class.java } ->
                    LocationResultLocationHooker
                else -> null
            }
            if (hooker != null && hookMethodOnce(method, hooker)) {
                hooks++
            }
        }
        ModuleRuntime.log("LocationResult hooks installed=$hooks")
    }

    fun alreadySpoofed(location: Location): Boolean {
        return location.extras?.containsKey(FAKED_EXTRA) == true
    }

    fun mark(location: Location) {
        val extras = location.extras ?: Bundle()
        extras.putBoolean(FAKED_EXTRA, true)
        location.extras = extras
    }

    /** Fresh spoofed Location, marked so later rewrite layers skip it. */
    fun remap(location: Location?): Location {
        // Already-spoofed inputs pass through untouched, like the original's
        // central rewrite function; without this, the micro-drift applied in
        // SpoofState.makeLocation would stack on every set/parcel pass.
        if (location != null && alreadySpoofed(location)) {
            return location
        }
        val provider = location?.provider
        val spoofed = SpoofState.makeLocation(provider)
        if (location != null) {
            if (location.time > 0L) {
                spoofed.time = location.time
            }
            if (location.elapsedRealtimeNanos > 0L) {
                spoofed.elapsedRealtimeNanos = location.elapsedRealtimeNanos
            }
            location.extras?.let { source ->
                // Keep the original fix's extras (e.g. framework satellite
                // fields) under our own synthesized keys, the way the original
                // preserved them when rewriting a real location.
                val merged = Bundle()
                merged.putAll(source)
                spoofed.extras?.let { merged.putAll(it) }
                spoofed.extras = merged
            }
        }
        mark(spoofed)
        return spoofed
    }

    /**
     * Field-by-field overwrite used by `writeToParcel`. Mutates [target] in
     * place so the original parcel writer emits the spoofed values.
     */
    fun overwrite(target: Location) {
        if (alreadySpoofed(target)) {
            return
        }
        val spoofed = remap(target)
        target.latitude = spoofed.latitude
        target.longitude = spoofed.longitude
        target.altitude = spoofed.altitude
        target.speed = spoofed.speed
        target.bearing = spoofed.bearing
        target.accuracy = spoofed.accuracy
        target.time = spoofed.time
        target.elapsedRealtimeNanos = spoofed.elapsedRealtimeNanos
        target.verticalAccuracyMeters = spoofed.verticalAccuracyMeters
        target.speedAccuracyMetersPerSecond = spoofed.speedAccuracyMetersPerSecond
        target.bearingAccuracyDegrees = spoofed.bearingAccuracyDegrees
        target.extras = spoofed.extras
    }

    fun rewriteLocationResult(result: Any?) {
        if (result == null) {
            return
        }
        val field = locationsField(result.javaClass) ?: return
        val current = try {
            field.get(result)
        } catch (_: Throwable) {
            return
        }
        val seed = when (current) {
            is List<*> -> current.firstOrNull { it is Location } as? Location
            is Array<*> -> current.firstOrNull { it is Location } as? Location
            else -> null
        }
        val spoofed = remap(seed)
        try {
            if (current is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val list = current as MutableList<Any?>
                list.clear()
                list.add(spoofed)
            } else {
                field.set(result, arrayListOf(spoofed))
            }
        } catch (_: Throwable) {
        }
    }

    fun remapList(value: Any?): Any? {
        if (value !is List<*>) {
            return value
        }
        val remapped = ArrayList<Any?>(value.size)
        var changed = false
        for (item in value) {
            if (item is Location) {
                remapped.add(remap(item))
                changed = true
            } else {
                remapped.add(item)
            }
        }
        return if (changed) remapped else value
    }

    private fun locationsField(cls: Class<*>): Field? {
        var named: Field? = null
        var typed: Field? = null
        var current: Class<*>? = cls
        while (current != null) {
            for (field in current.declaredFields) {
                if (field.name == "mLocations") {
                    named = field
                } else if (typed == null && List::class.java.isAssignableFrom(field.type)) {
                    typed = field
                }
            }
            current = current.superclass
        }
        val field = named ?: typed ?: return null
        field.isAccessible = true
        return field
    }
}

object LocationParcelHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        val location = chain.thisObject as? Location ?: return chain.proceed()
        BasicLocationHooks.overwrite(location)
        HookSupport.logHit(chain.executable, "location parcel rewritten")
        return chain.proceed()
    }
}

object LocationSetHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        val args = chain.args
        if (args.isEmpty()) {
            return chain.proceed()
        }
        val source = args[0] as? Location ?: return chain.proceed()
        val newArgs = args.toTypedArray()
        newArgs[0] = BasicLocationHooks.remap(source)
        HookSupport.logHit(chain.executable, "location set remapped")
        return chain.proceed(newArgs)
    }
}

object LocationResultAsListHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        if (!ModuleRuntime.active()) {
            return result
        }
        val remapped = BasicLocationHooks.remapList(result)
        if (remapped !== result) {
            HookSupport.logHit(chain.executable, "location result asList remapped")
        }
        return remapped
    }
}

object LocationResultParcelHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.active()) {
            BasicLocationHooks.rewriteLocationResult(chain.thisObject)
            HookSupport.logHit(chain.executable, "location result parcel rewritten")
        }
        return chain.proceed()
    }
}

object LocationResultLocationHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        var replaced = false
        val newArgs = chain.args.toTypedArray()
        for (i in newArgs.indices) {
            val arg = newArgs[i]
            if (arg is Location) {
                newArgs[i] = BasicLocationHooks.remap(arg)
                replaced = true
            }
        }
        if (replaced) {
            HookSupport.logHit(chain.executable, "location result arg remapped")
        }
        val result = if (replaced) chain.proceed(newArgs) else chain.proceed()
        val location = result as? Location ?: return result
        HookSupport.logHit(chain.executable, "location result return remapped")
        return BasicLocationHooks.remap(location)
    }
}
