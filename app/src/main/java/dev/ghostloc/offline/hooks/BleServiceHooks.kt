package dev.ghostloc.offline.hooks

import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.findClass
import dev.ghostloc.offline.core.ModuleRuntime.hookMethodOnce
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

/**
 * Server-side BLE scan suppression inside `com.android.bluetooth`.
 *
 * Indoor positioning (store/mall beacons used by QQ/WeChat and similar)
 * never goes through LocationManager. Swallowing scan start and scan
 * results in the bluetooth process hides that radio picture from every
 * app, scoped or not.
 *
 * Class names vary across AOSP / OEM BLE stacks; missing candidates are
 * logged and skipped.
 */
object BleServiceHooks {

    private val TARGET_CLASSES = listOf(
        "com.android.bluetooth.gatt.GattService",
        "com.android.bluetooth.gatt.GattNativeInterface",
        "com.android.bluetooth.gatt.ScanManager",
        "com.android.bluetooth.le_scan.ScanManager",
        "com.android.bluetooth.btservice.adapter.ScanManager",
        "android.bluetooth.le.IScannerCallback\$Stub",
        "android.bluetooth.le.IScannerCallback\$Stub\$Proxy",
        "android.bluetooth.le.BluetoothLeScanner",
    )

    private val SWALLOW_METHODS = setOf(
        "startScan",
        "onScanResult",
        "onBatchScanResults",
        "onScan",
        "onFoundOrLost",
    )

    fun install(loader: ClassLoader?) {
        var total = 0
        for (className in TARGET_CLASSES) {
            total += hookClass(loader, className)
        }
        ModuleRuntime.log("ble service hooks installed=$total")
    }

    private fun hookClass(loader: ClassLoader?, className: String): Int {
        val cls = findClass(loader, className)
        if (cls == null) {
            ModuleRuntime.log("ble class missing $className")
            return 0
        }
        var hooks = 0
        for (method in cls.declaredMethods) {
            if (Modifier.isAbstract(method.modifiers) || method.isSynthetic) {
                continue
            }
            if (method.name !in SWALLOW_METHODS) {
                continue
            }
            if (hookMethodOnce(method, BleSwallowHooker)) {
                hooks++
            }
        }
        ModuleRuntime.log("ble hooks $className installed=$hooks")
        return hooks
    }
}

object BleSwallowHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!ModuleRuntime.active()) {
            return chain.proceed()
        }
        HookSupport.logHit(chain.executable, "ble scan swallowed")
        return HookSupport.skipWithNeutralResult(chain)
    }
}
