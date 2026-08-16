# GhostLoc Offline

Clean-room, offline Android location control module for owned test devices.

No login, no authorization server, no challenge flow, no native guard, and no
copied code or assets from GhostMapX.

## Status

Current state: **Kotlin rewrite (0.2.0), verified end to end on the owned
device** (OnePlus 7 / GM1901, Android 15 / SDK 35, Vector/LSPosed API 100).

The whole module (hook engine + UI) is Kotlin. The libxposed entry class FQCN
`dev.ghostloc.offline.GhostLocModule` is unchanged, so existing LSPosed
registrations keep working.

Verified on device (2026-08-16):

- module loads in app/system_server processes, service binds `LSPosed API 100`
- map-based pick with GCJ-02→WGS-84 correction, single tap places, long press
  places and starts
- route movement (multi-point JSON, loop, interpolation), straight movement,
  speed variation, accuracy/altitude jitter
- `getLastKnownLocation` / `getCurrentLocation` / listener / fused spoofing
- deep `system_server` hook hits (`LocationManagerService`,
  `LocationProviderManager`, `ILocationManager$Stub`)
- Wi-Fi, Geocoder, Bluetooth masking
- LTE + NR cell synthesis, operator presets, `ServiceState`,
  `requestCellInfoUpdate` callback
- GNSS status and NMEA sentence synthesis
- `probe-app` reports PASS; ordinary `demo-app` receives the spoofed route
  output; stop disables spoofing (probe reverts to FAIL/null, matching the
  Java baseline behavior)

Not yet visually verified: live tile download (the validation environment's
Wi-Fi was behind an unauthenticated captive portal with no internet). The
tile pipeline is configured (AMap direct HTTPS, persistent cache) and becomes
active as soon as the device has working internet.

## Modules

- `app`: Android UI (Kotlin, four tabs) plus the libxposed module entry.
- `probe-app`: owned test app used to verify hook output on real devices.
- `demo-app`: owned ordinary location client (public APIs only).
- `vector-api-stubs`: compile-time stubs for the libxposed modern API.

## Architecture

```
app/src/main/java/dev/ghostloc/offline/
  GhostLocModule.kt        # libxposed entry (FQCN stable)
  ModuleApplication.kt     # XposedService binding + osmdroid global config
  MainActivity.kt          # status bar + 4-tab pager, commit/bridge logic
  core/
    Prefs.kt               # remote-pref keys and defaults (stable values)
    ModuleRuntime.kt       # bridge/prefs/logging/diagnostics/reflection toolkit
    SpoofState.kt          # route interpolation, movement, jitter
    GeoMath.kt             # spherical math + GCJ-02 <-> WGS-84 conversion
    Bridge.kt              # sendExtraCommand("ghostloc", ...) command bridge
  hooks/                   # per-surface hook groups + Hooker classes
  synth/                   # CellSynth / GnssSynth / NmeaSynth / AddressSynth
  ui/                      # fragments, tile sources, search, draft state
```

Hooker classes use the Kotlin pattern
`class XHooker : XposedInterface.Hooker { companion object { @JvmStatic fun before/after(...) } }`
(standard form for libxposed API 100).

## Map sources

Default tile source is AMap (`webst0X.is.autonavi.com`), reachable from
mainland China without a proxy. AMap tiles are drawn in GCJ-02, so picked
coordinates are corrected to WGS-84 before feeding the hook layer (toggle in
the map tab, on by default). OSM Mapnik remains selectable for use behind a
VPN or abroad. Tile cache lives in app-internal storage
(`files/osmdroid`) and survives reboots.

Search uses the AMap Web Service API when a key is configured (diagnostics
tab), falling back to the system Geocoder.

## Build

```powershell
.\gradlew.bat :app:assembleDebug :probe-app:assembleDebug :demo-app:assembleDebug
```

APKs:

```text
app\build\outputs\apk\debug\app-debug.apk
probe-app\build\outputs\apk\debug\probe-app-debug.apk
demo-app\build\outputs\apk\debug\demo-app-debug.apk
```

Gradle uses Aliyun mirrors first, then the official repos, so builds work
with or without a proxy on this machine.

## Runtime notes

Install on a rooted device with a libxposed/LSPosed-compatible runtime,
enable the module, select scopes, and **reboot**.

Important operational details:

- **Module APK updates require a reboot.** After `adb install -r`, the
  framework keeps injecting the APK path recorded at boot. If the install
  directory changes, update `modules.apk_path` in the LSPosed/Vector config
  database (` /data/adb/lspd/config/modules_config.db`) or just reboot.
- **Do not run the original GhostMapX module at the same time.** Both hook the
  same location surfaces in `system_server`; they were verified to conflict.
  GhostMapX is currently disabled in the framework DB on the validation
  device; re-enable it manually only if this module is turned off.
- Core settings live in the remote `settings` preference group shared by the
  UI and all hooked processes; probe reports and UI-only keys stay local.

Scope set used on the owned device:

```text
android  system  com.android.phone  com.android.bluetooth
com.android.location.fused (optional)
dev.ghostloc.offline  dev.ghostloc.probe  dev.ghostloc.demo
```

## User docs

- [docs/MODULE_USER_GUIDE.md](docs/MODULE_USER_GUIDE.md)

## Traceability

- Work log: [docs/TRACEABILITY.md](docs/TRACEABILITY.md)
- Device report: [docs/DEVICE_TEST_REPORT.md](docs/DEVICE_TEST_REPORT.md)
- Plan: [docs/REPLICATION_PLAN.md](docs/REPLICATION_PLAN.md)
