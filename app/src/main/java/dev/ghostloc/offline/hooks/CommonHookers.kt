package dev.ghostloc.offline.hooks

import dev.ghostloc.offline.core.ModuleRuntime
import io.github.libxposed.api.XposedInterface
import java.util.Collections

/** Hookers shared across several hook groups. */

class FalseHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.mockShield()) {
                callback.returnAndSkip(false)
            }
        }
    }
}

class EmptyListHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield() || ModuleRuntime.cellShield()) {
                callback.returnAndSkip(Collections.emptyList<Any>())
            }
        }
    }
}
