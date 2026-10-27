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
import dev.ghostloc.offline.hooks.SdkHooks
import dev.ghostloc.offline.hooks.SettingsHooks
import dev.ghostloc.offline.hooks.TelephonyHooks
import dev.ghostloc.offline.hooks.VendorHooks
import dev.ghostloc.offline.hooks.WifiHooks
import dev.ghostloc.offline.hooks.WifiServiceHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed modern-API entry point (API 102). The FQCN is referenced by
 * META-INF/xposed/java_init.list and by existing framework registrations, so
 * it must not change. The framework instantiates the class through its
 * no-arg constructor and drives it through [onModuleLoaded].
 */
class GhostLocModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModuleRuntime.init(this)
        ModuleRuntime.recordProcess(param.processName)
        ModuleRuntime.log("loaded process=${param.processName}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        LocationManagerHooks.install()
        DeepServiceHooks.install(param.classLoader)
        BasicLocationHooks.install(param.classLoader)
        SettingsHooks.install()
        WifiServiceHooks.install(param.classLoader)
        ModuleRuntime.log("system_server hooks installed")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName
        if (SELF_PACKAGE == packageName) {
            // Never hook the module's own process: self-hooking breaks the
            // UI's Geocoder-based place search and provides no benefit (the
            // bridge talks to system_server over plain binder).
            ModuleRuntime.log("self package observed")
            return
        }
        LocationManagerHooks.install()
        LocationObjectHooks.install()
        GeocoderHooks.install()
        SettingsHooks.install()
        WifiHooks.install()
        TelephonyHooks.install()
        BluetoothHooks.install()
        VendorHooks.install(param.defaultClassLoader, packageName)
        SdkHooks.install(param.defaultClassLoader)
        if (packageName == "com.android.phone") {
            PhoneServiceHooks.install(param.defaultClassLoader)
        }
        if (packageName == "com.android.bluetooth") {
            BleServiceHooks.install(param.defaultClassLoader)
        }
    }
}
