package dev.ghostloc.offline.hooks

import android.bluetooth.BluetoothAdapter
import android.location.Location
import android.provider.Settings
import dev.ghostloc.offline.core.ModuleRuntime
import io.github.libxposed.api.XposedInterface

object LocationObjectHooks {

    fun install() {
        try {
            hookBooleanMethod(Location::class.java, "isFromMockProvider")
            hookBooleanMethod(Location::class.java, "isMock")
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Location object hook failed", throwable)
        }
    }

    private fun hookBooleanMethod(cls: Class<*>, name: String) {
        try {
            val method = cls.getDeclaredMethod(name)
            ModuleRuntime.hook(method, FalseHooker)
        } catch (_: NoSuchMethodException) {
        }
    }
}

object SettingsHooks {

    fun install() {
        try {
            for (method in Settings.Secure::class.java.declaredMethods) {
                when (method.name) {
                    "getString", "getStringForUser" ->
                        ModuleRuntime.hook(method, SecureStringHooker)
                    "getInt", "getIntForUser" ->
                        ModuleRuntime.hook(method, SecureIntHooker)
                }
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Settings.Secure hook failed", throwable)
        }
    }
}

object BluetoothHooks {

    fun install() {
        try {
            val method = BluetoothAdapter::class.java.getDeclaredMethod("getAddress")
            ModuleRuntime.hook(method, BluetoothAddressHooker)
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Bluetooth hook skipped", throwable)
        }
    }
}

object BluetoothAddressHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield()) {
            return "02:00:00:00:00:00"
        }
        return chain.proceed()
    }
}

object SecureStringHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.mockShield() && HookSupport.hasMockLocationKey(chain.args)) {
            return "0"
        }
        return chain.proceed()
    }
}

object SecureIntHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.mockShield() && HookSupport.hasMockLocationKey(chain.args)) {
            return 0
        }
        return chain.proceed()
    }
}
