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
            val hooker: Class<out XposedInterface.Hooker>? = when (method.name) {
                "writeToParcel" -> LocationParcelHooker::class.java
                "set" -> LocationSetHooker::class.java
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
            val hooker: Class<out XposedInterface.Hooker>? = when {
                method.name == "asList" -> LocationResultAsListHooker::class.java
                method.name == "writeToParcel" -> LocationResultParcelHooker::class.java
                method.returnType == Location::class.java ||
                    method.parameterTypes.any { it == Location::class.java } ->
                    LocationResultLocationHooker::class.java
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
        val provider = location?.provider
        val spoofed = SpoofState.makeLocation(provider)
        if (location != null) {
            if (location.time > 0L) {
                spoofed.time = location.time
            }
            if (location.elapsedRealtimeNanos > 0L) {
                spoofed.elapsedRealtimeNanos = location.elapsedRealtimeNanos
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

class LocationParcelHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            val location = callback.thisObject as? Location ?: return
            BasicLocationHooks.overwrite(location)
            HookSupport.logHit(callback.member, "location parcel rewritten")
        }
    }
}

class LocationSetHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            val args = callback.args ?: return
            if (args.isEmpty()) {
                return
            }
            val source = args[0] as? Location ?: return
            args[0] = BasicLocationHooks.remap(source)
            HookSupport.logHit(callback.member, "location set remapped")
        }
    }
}

class LocationResultAsListHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            if (!ModuleRuntime.active() || callback.throwable != null) {
                return
            }
            val remapped = BasicLocationHooks.remapList(callback.result)
            if (remapped !== callback.result) {
                callback.result = remapped
                HookSupport.logHit(callback.member, "location result asList remapped")
            }
        }
    }
}

class LocationResultParcelHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.active()) {
                return
            }
            BasicLocationHooks.rewriteLocationResult(callback.thisObject)
            HookSupport.logHit(callback.member, "location result parcel rewritten")
        }
    }
}

class LocationResultLocationHooker : XposedInterface.Hooker {
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
                HookSupport.logHit(callback.member, "location result arg remapped")
            }
        }

        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            if (!ModuleRuntime.active() || callback.throwable != null) {
                return
            }
            val result = callback.result as? Location ?: return
            callback.result = BasicLocationHooks.remap(result)
            HookSupport.logHit(callback.member, "location result return remapped")
        }
    }
}
