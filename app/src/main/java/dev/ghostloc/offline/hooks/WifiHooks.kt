package dev.ghostloc.offline.hooks

import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import dev.ghostloc.offline.core.ModuleRuntime
import io.github.libxposed.api.XposedInterface

object WifiHooks {

    fun install() {
        try {
            for (method in WifiManager::class.java.declaredMethods) {
                when (method.name) {
                    "getScanResults" -> ModuleRuntime.bridge?.hook(method, EmptyListHooker::class.java)
                    "isScanAlwaysAvailable" -> ModuleRuntime.bridge?.hook(method, FalseHooker::class.java)
                }
            }
            for (method in WifiInfo::class.java.declaredMethods) {
                val hooker: Class<out XposedInterface.Hooker> = when (method.name) {
                    "getMacAddress", "getBSSID" -> WifiAddressHooker::class.java
                    "getSSID" -> WifiSsidHooker::class.java
                    "getRssi" -> WifiRssiHooker::class.java
                    "getLinkSpeed" -> WifiLinkSpeedHooker::class.java
                    "getFrequency" -> WifiFrequencyHooker::class.java
                    "getNetworkId" -> WifiNetworkIdHooker::class.java
                    "getIpAddress" -> WifiIpHooker::class.java
                    "getSupplicantState" -> WifiSupplicantHooker::class.java
                    "getHiddenSSID" -> FalseHooker::class.java
                    else -> continue
                }
                ModuleRuntime.bridge?.hook(method, hooker)
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Wi-Fi hook failed", throwable)
        }
    }
}

class WifiAddressHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip("02:00:00:00:00:00")
            }
        }
    }
}

class WifiSsidHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip("\"GhostLoc\"")
            }
        }
    }
}

class WifiRssiHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip(-55)
            }
        }
    }
}

class WifiLinkSpeedHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip(72)
            }
        }
    }
}

class WifiFrequencyHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip(2412)
            }
        }
    }
}

class WifiNetworkIdHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip(0)
            }
        }
    }
}

class WifiIpHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip(0)
            }
        }
    }
}

class WifiSupplicantHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.wifiShield()) {
                callback.returnAndSkip(SupplicantState.COMPLETED)
            }
        }
    }
}
