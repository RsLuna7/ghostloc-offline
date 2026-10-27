package dev.ghostloc.offline.hooks

import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.findClass
import dev.ghostloc.offline.core.ModuleRuntime.hookMethodOnce
import io.github.libxposed.api.XposedInterface

/**
 * Server-side cell synthesis in the `com.android.phone` process.
 *
 * `TelephonyHooks` patch the `TelephonyManager` client class, which only
 * changes answers inside scoped processes. The binder implementation that
 * actually answers every app's telephony query is `PhoneInterfaceManager`
 * in com.android.phone (already in the module scope), so hooking it here
 * serves synthetic cell data to out-of-scope apps such as QQ.
 */
object PhoneServiceHooks {

    private const val IMPL_CLASS = "com.android.phone.PhoneInterfaceManager"

    fun install(loader: ClassLoader?) {
        val cls = findClass(loader, IMPL_CLASS)
        if (cls == null) {
            ModuleRuntime.log("phone service impl not found")
            return
        }
        var hooks = 0
        for (method in cls.declaredMethods) {
            val name = method.name
            val hooker: XposedInterface.Hooker? = when {
                name == "getAllCellInfo" -> CellInfoHooker
                name == "getCellLocation" -> CellLocationNullHooker
                name == "requestCellInfoUpdate" || name == "requestCellInfoUpdateWithWorkSource" ->
                    RequestCellInfoUpdateHooker
                name.startsWith("getNetworkType") || name.startsWith("getDataNetworkType") ||
                    name.startsWith("getVoiceNetworkType") -> NetworkTypeHooker
                name.startsWith("getServiceState") -> ServiceStateHooker
                name == "getNeighboringCellInfo" -> NeighborCellEmptyHooker
                name == "getNetworkOperator" || name == "getSimOperator" -> OperatorCodeHooker
                name == "getNetworkOperatorName" || name == "getSimOperatorName" -> OperatorNameHooker
                name == "getNetworkCountryIso" || name == "getSimCountryIso" -> CountryIsoHooker
                name == "getActivePhoneType" || name == "getActivePhoneTypeForSlot" -> PhoneTypeHooker
                else -> null
            }
            if (hooker != null && hookMethodOnce(method, hooker)) {
                hooks++
            }
        }
        ModuleRuntime.log("phone service hooks installed=$hooks")
    }
}

/**
 * Pull side of the legacy cell-location channel. Serves the same synthetic
 * bundle the TelephonyRegistry push delivers, so polling and listening apps
 * see one consistent cell (the original faked both channels with CDMA data).
 */
object CellLocationNullHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.cellShield()) {
            HookSupport.logHit(chain.executable, "phone cell location masked")
            return dev.ghostloc.offline.synth.CellSynth.makeCellLocationBundle()
        }
        return chain.proceed()
    }
}

object NeighborCellEmptyHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (ModuleRuntime.cellShield()) {
            return ArrayList<Any>()
        }
        return chain.proceed()
    }
}
