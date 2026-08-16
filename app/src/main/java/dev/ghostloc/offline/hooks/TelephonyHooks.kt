package dev.ghostloc.offline.hooks

import android.telephony.ServiceState
import android.telephony.TelephonyManager
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.synth.CellSynth
import io.github.libxposed.api.XposedInterface

object TelephonyHooks {

    fun install() {
        try {
            for (method in TelephonyManager::class.java.declaredMethods) {
                val name = method.name
                val hooker: Class<out XposedInterface.Hooker> = when {
                    name == "getAllCellInfo" -> CellInfoHooker::class.java
                    name == "requestCellInfoUpdate" -> RequestCellInfoUpdateHooker::class.java
                    name == "getNetworkType" || name == "getDataNetworkType" || name == "getVoiceNetworkType" ->
                        NetworkTypeHooker::class.java
                    name == "isNetworkRoaming" -> FalseHooker::class.java
                    name == "getServiceState" && method.returnType == ServiceState::class.java ->
                        ServiceStateHooker::class.java
                    name == "getPhoneType" -> PhoneTypeHooker::class.java
                    name == "getNetworkOperator" || name == "getSimOperator" -> OperatorCodeHooker::class.java
                    name == "getNetworkOperatorName" || name == "getSimOperatorName" -> OperatorNameHooker::class.java
                    name == "getNetworkCountryIso" || name == "getSimCountryIso" -> CountryIsoHooker::class.java
                    else -> continue
                }
                ModuleRuntime.bridge?.hook(method, hooker)
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Telephony hook failed", throwable)
        }
    }
}

class CellInfoHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.cellShield()) {
                callback.returnAndSkip(CellSynth.makeCellInfoList())
            }
        }
    }
}

class RequestCellInfoUpdateHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.cellShield()) {
                return
            }
            val cells = CellSynth.makeCellInfoList()
            if (CellSynth.dispatchCellInfoCallback(callback.args, cells)) {
                HookSupport.logHit(callback.member, "cell callback")
                callback.returnAndSkip(null)
            }
        }
    }
}

class NetworkTypeHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (!ModuleRuntime.cellShield()) {
                return
            }
            val profile = CellSynth.currentCellProfile()
            val name = (callback.member as? java.lang.reflect.Method)?.name ?: ""
            val value = when {
                name == "getVoiceNetworkType" -> TelephonyManager.NETWORK_TYPE_LTE
                profile.nrEnabled -> TelephonyManager.NETWORK_TYPE_NR
                else -> TelephonyManager.NETWORK_TYPE_LTE
            }
            callback.returnAndSkip(value)
        }
    }
}

class PhoneTypeHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.cellShield()) {
                callback.returnAndSkip(TelephonyManager.PHONE_TYPE_GSM)
            }
        }
    }
}

class OperatorCodeHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.cellShield()) {
                callback.returnAndSkip(CellSynth.currentCellProfile().operatorNumeric)
            }
        }
    }
}

class OperatorNameHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.cellShield()) {
                callback.returnAndSkip(CellSynth.currentCellProfile().operatorName)
            }
        }
    }
}

class CountryIsoHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.cellShield()) {
                callback.returnAndSkip(CellSynth.currentCellProfile().countryIso)
            }
        }
    }
}

class ServiceStateHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: XposedInterface.BeforeHookCallback) {
            if (ModuleRuntime.cellShield()) {
                callback.returnAndSkip(CellSynth.makeServiceState())
            }
        }
    }
}
