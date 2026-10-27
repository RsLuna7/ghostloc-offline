package dev.ghostloc.offline.hooks

import android.telephony.ServiceState
import android.telephony.TelephonyManager
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.synth.CellSynth
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

object TelephonyHooks {

    fun install() {
        try {
            for (method in TelephonyManager::class.java.declaredMethods) {
                val name = method.name
                val hooker: XposedInterface.Hooker = when {
                    name == "getAllCellInfo" -> CellInfoHooker
                    name == "requestCellInfoUpdate" -> RequestCellInfoUpdateHooker
                    name == "getNetworkType" || name == "getDataNetworkType" || name == "getVoiceNetworkType" ->
                        NetworkTypeHooker
                    name == "isNetworkRoaming" -> FalseHooker
                    name == "getServiceState" && method.returnType == ServiceState::class.java ->
                        ServiceStateHooker
                    name == "getPhoneType" -> PhoneTypeHooker
                    name == "getNetworkOperator" || name == "getSimOperator" -> OperatorCodeHooker
                    name == "getNetworkOperatorName" || name == "getSimOperatorName" -> OperatorNameHooker
                    name == "getNetworkCountryIso" || name == "getSimCountryIso" -> CountryIsoHooker
                    else -> continue
                }
                ModuleRuntime.hook(method, hooker)
            }
        } catch (throwable: Throwable) {
            ModuleRuntime.log("Telephony hook failed", throwable)
        }
    }
}

object CellInfoHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.cellShield()) {
            return CellSynth.makeCellInfoList()
        }
        return chain.proceed()
    }
}

object RequestCellInfoUpdateHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.cellShield()) {
            return chain.proceed()
        }
        val cells = CellSynth.makeCellInfoList()
        if (CellSynth.dispatchCellInfoCallback(HookSupport.argArray(chain), cells)) {
            HookSupport.logHit(chain.executable, "cell callback")
            return null
        }
        return chain.proceed()
    }
}

object NetworkTypeHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.cellShield()) {
            return chain.proceed()
        }
        val profile = CellSynth.currentCellProfile()
        val name = (chain.executable as? Method)?.name ?: ""
        return when {
            name == "getVoiceNetworkType" -> TelephonyManager.NETWORK_TYPE_LTE
            profile.nrEnabled -> TelephonyManager.NETWORK_TYPE_NR
            else -> TelephonyManager.NETWORK_TYPE_LTE
        }
    }
}

object PhoneTypeHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.cellShield()) {
            return TelephonyManager.PHONE_TYPE_GSM
        }
        return chain.proceed()
    }
}

object OperatorCodeHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.cellShield()) {
            return CellSynth.currentCellProfile().operatorNumeric
        }
        return chain.proceed()
    }
}

object OperatorNameHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.cellShield()) {
            return CellSynth.currentCellProfile().operatorName
        }
        return chain.proceed()
    }
}

object CountryIsoHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.cellShield()) {
            return CellSynth.currentCellProfile().countryIso
        }
        return chain.proceed()
    }
}

object ServiceStateHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.cellShield()) {
            return CellSynth.makeServiceState()
        }
        return chain.proceed()
    }
}
