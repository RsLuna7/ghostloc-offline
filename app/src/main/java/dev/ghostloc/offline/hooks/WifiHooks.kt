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
                    "getScanResults" -> ModuleRuntime.hook(method, EmptyListHooker)
                    "isScanAlwaysAvailable" -> ModuleRuntime.hook(method, FalseHooker)
                }
            }
            for (method in WifiInfo::class.java.declaredMethods) {
                val hooker: XposedInterface.Hooker = when (method.name) {
                    "getMacAddress", "getBSSID" -> WifiAddressHooker
                    "getSSID" -> WifiSsidHooker
                    "getRssi" -> WifiRssiHooker
                    "getLinkSpeed" -> WifiLinkSpeedHooker
                    "getFrequency" -> WifiFrequencyHooker
                    "getNetworkId" -> WifiNetworkIdHooker
                    "getIpAddress" -> WifiIpHooker
                    "getSupplicantState" -> WifiSupplicantHooker
                    "getHiddenSSID" -> FalseHooker
                    else -> continue
                }
                ModuleRuntime.hook(method, hooker)
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Wi-Fi hook failed", throwable)
        }
    }
}

object WifiAddressHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield()) {
            return "02:00:00:00:00:00"
        }
        return chain.proceed()
    }
}

object WifiSsidHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield()) {
            return "\"GhostLoc\""
        }
        return chain.proceed()
    }
}

object WifiRssiHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield()) {
            return -55
        }
        return chain.proceed()
    }
}

object WifiLinkSpeedHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield()) {
            return 72
        }
        return chain.proceed()
    }
}

object WifiFrequencyHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield()) {
            return 2412
        }
        return chain.proceed()
    }
}

object WifiNetworkIdHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield()) {
            return 0
        }
        return chain.proceed()
    }
}

object WifiIpHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield()) {
            return 0
        }
        return chain.proceed()
    }
}

object WifiSupplicantHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.wifiShield()) {
            return SupplicantState.COMPLETED
        }
        return chain.proceed()
    }
}
