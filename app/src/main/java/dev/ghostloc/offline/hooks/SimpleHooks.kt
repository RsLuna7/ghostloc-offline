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
            ModuleRuntime.bridge?.hook(method, FalseHooker::class.java)
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
                        ModuleRuntime.bridge?.hook(method, SecureStringHooker::class.java)
                    "getInt", "getIntForUser" ->
                        ModuleRuntime.bridge?.hook(method, SecureIntHooker::class.java)
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
            ModuleRuntime.bridge?.hook(method, BluetoothAddressHooker::class.java)
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Bluetooth hook skipped", throwable)
        }
    }
}

class BluetoothAddressHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip("02:00:00:00:00:00")
            }
        }
    }
}

class SecureStringHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.mockShield() && HookSupport.hasMockLocationKey(callback.args)) {
                callback.returnAndSkip("0")
            }
        }
    }
}

class SecureIntHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.mockShield() && HookSupport.hasMockLocationKey(callback.args)) {
                callback.returnAndSkip(0)
            }
        }
    }
}
