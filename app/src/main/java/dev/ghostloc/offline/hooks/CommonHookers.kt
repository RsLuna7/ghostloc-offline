package dev.ghostloc.offline.hooks

import dev.ghostloc.offline.core.ModuleRuntime
import io.github.libxposed.api.XposedInterface
import java.util.Collections

/** Hookers shared across several hook groups. */

object FalseHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.mockShield()) {
            return false
        }
        return chain.proceed()
    }
}

object EmptyListHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield() || ModuleRuntime.cellShield()) {
            return Collections.emptyList<Any>()
        }
        return chain.proceed()
    }
}
