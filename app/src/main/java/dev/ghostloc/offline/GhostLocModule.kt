package dev.ghostloc.offline

import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.SELF_PACKAGE
import dev.ghostloc.offline.hooks.BasicLocationHooks
import dev.ghostloc.offline.hooks.BleServiceHooks
import dev.ghostloc.offline.hooks.BluetoothHooks
import dev.ghostloc.offline.hooks.DeepServiceHooks
import dev.ghostloc.offline.hooks.GeocoderHooks
import dev.ghostloc.offline.hooks.LocationManagerHooks
import dev.ghostloc.offline.hooks.LocationObjectHooks
import dev.ghostloc.offline.hooks.PhoneServiceHooks
import dev.ghostloc.offline.hooks.SettingsHooks
import dev.ghostloc.offline.hooks.SdkHooks
import dev.ghostloc.offline.hooks.TelephonyHooks
import dev.ghostloc.offline.hooks.VendorHooks
import dev.ghostloc.offline.hooks.WifiHooks
import dev.ghostloc.offline.hooks.WifiServiceHooks
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerLoadedParam

/**
 * libxposed modern-API entry point. The FQCN is referenced by
 * META-INF/xposed/java_init.list and by existing LSPosed registrations, so it
 * must not change.
 */
class GhostLocModule(base: XposedInterface, param: ModuleLoadedParam) : XposedModule(base, param) {

    init {
        ModuleRuntime.init(base)
        ModuleRuntime.recordProcess(param.processName)
        ModuleRuntime.log("loaded process=${param.processName}")
    }

    override fun onSystemServerLoaded(param: SystemServerLoadedParam) {
        LocationManagerHooks.install()
        DeepServiceHooks.install(param.classLoader)
        BasicLocationHooks.install(param.classLoader)
        SettingsHooks.install()
        WifiServiceHooks.install(param.classLoader)
        ModuleRuntime.log("system_server hooks installed")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName
        LocationManagerHooks.install()
        LocationObjectHooks.install()
        GeocoderHooks.install()
        SettingsHooks.install()
        WifiHooks.install()
        TelephonyHooks.install()
        BluetoothHooks.install()
        VendorHooks.install(param.classLoader, packageName)
        SdkHooks.install(param.classLoader)
        if (packageName == "com.android.phone") {
            PhoneServiceHooks.install(param.classLoader)
        }
        if (packageName == "com.android.bluetooth") {
            BleServiceHooks.install(param.classLoader)
        }
        if (SELF_PACKAGE == packageName) {
            ModuleRuntime.log("self package observed")
        }
    }
}
