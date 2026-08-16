# GhostLoc Offline

Clean-room, offline Android location control module for owned test devices.

No login, no authorization server, no challenge flow, no native guard, and no
copied code or assets from GhostMapX.

## Status

Current state: **0.3.0 — Kotlin, live state sync, Material 3 UI. Verified
end to end on the owned device** (OnePlus 7 / GM1901, Android 15 / SDK 35,
Vector/LSPosed API 100).

Verified on device (2026-08-16):

- module loads in app/system_server processes, service binds `LSPosed API 100`
- map pick with GCJ-02→WGS-84 correction; long press places and starts
- **live coordinate switching without reboot**: Wuhan → Beijing → Shenzhen
  changes took effect immediately through the system_server path, for scoped
  and unscoped apps alike
- route movement (multi-point JSON, loop, interpolation), straight movement,
  speed variation, accuracy/altitude jitter
- deep `system_server` hook hits (`LocationManagerService`,
  `LocationProviderManager`, `ILocationManager$Stub`)
- Wi-Fi, Geocoder, Bluetooth masking; LTE + NR cell synthesis with operator
  presets; GNSS status and NMEA synthesis
- `probe-app` PASS; ordinary `demo-app` receives live spoofed output; stop
  disables spoofing (probe reverts to FAIL/null)

Known behavior notes:

- `getCurrentLocation` returns null while spoofing; apps fall back to
  `getLastKnownLocation`/listeners, which serve live spoofed values
- client-only hooks (GNSS/NMEA/Wi-Fi/cell settings) read boot-time
  configuration; changing those toggles requires restarting the target app
- live tile download was not visually verified (validation Wi-Fi was behind
  an unauthenticated captive portal); the pipeline is configured and works as
  soon as the device has internet

## Modules

- `app`: Android UI (Kotlin, Material 3, four tabs) plus the libxposed module entry.
- `probe-app`: owned test app used to verify hook output on real devices.
- `demo-app`: owned ordinary location client (public APIs only).
- `vector-api-stubs`: compile-time stubs for the libxposed modern API.

## Architecture

```
app/src/main/java/dev/ghostloc/offline/
  GhostLocModule.kt        # libxposed entry (FQCN stable)
  ModuleApplication.kt     # XposedService binding + osmdroid global config
  MainActivity.kt          # status header + 4-tab pager, commit/sync logic
  core/
    Prefs.kt               # preference keys and defaults (stable values)
    ModuleRuntime.kt       # bridge/prefs/logging/diagnostics/reflection toolkit
    SpoofState.kt          # route interpolation, movement, jitter
    GeoMath.kt             # spherical math + GCJ-02 <-> WGS-84 conversion
    Bridge.kt              # sendExtraCommand command handlers (system_server side)
    StateStore.kt          # live-state file + MergedPrefs overlay
  hooks/                   # per-surface hook groups + Hooker classes
  synth/                   # CellSynth / GnssSynth / NmeaSynth / AddressSynth
  ui/                      # fragments, tile sources, search, draft state
scripts/device/
  99ghostloc.sh            # Magisk service.d script (see Runtime notes)
```

### Live state sync (important)

On the current framework (Vector) both preference channels available to a
module are dead ends for live updates: hook-side remote preferences are
read-only, and UI-side service writes land in a WAL the daemon never reads.
The module therefore syncs state through the location command bridge:

1. Every UI commit writes the local `settings` xml **and** sends a full
   `sync` command via `LocationManager.sendExtraCommand("ghostloc", ...)`.
2. The hook on `LocationManagerService#sendExtraCommand` runs inside
   system_server, persists the state to `/data/misc/ghostloc/state.properties`
   (only system_server can access it), and skips the original call.
3. system_server's location hooks answer every app's location request from
   that file — so **all apps see live values, no reboot or app restart
   needed**, scoped or not.
4. Client-side location spoofing was removed (binder answers are
   authoritative). Client-only surfaces (GNSS/NMEA/Wi-Fi/cell) still hook
   in-process and read boot-time configuration.

Hooker classes use the Kotlin pattern
`class XHooker : XposedInterface.Hooker { companion object { @JvmStatic fun before/after(...) } }`.

### UI

Material 3 with a teal scheme: status header card, four tabs (地图 / 参数 /
路线·收藏 / 诊断), card sections, Material switches, styled buttons, and a
collapsible "高级设置" section on the params tab. The app has an adaptive
launcher icon.

Map sources default to AMap (`webst0X.is.autonavi.com`, reachable from
mainland China without a proxy; tiles drawn in GCJ-02, picked coordinates are
corrected to WGS-84). OSM Mapnik remains selectable. Tile cache lives in
app-internal storage (`files/osmdroid`). Search uses the AMap Web Service API
when a key is configured (diagnostics tab), falling back to the system
Geocoder.

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
enable the module, select scopes, and reboot. Operational details that
matter on the validation device:

- **Module APK updates require a reboot.** After `adb install -r`, the
  framework keeps injecting the APK path recorded at boot. If the install
  directory changed, refresh `modules.apk_path` in
  `/data/adb/lspd/config/modules_config.db` (or just reboot).
- **The scope must include the module's own package**
  (`dev.ghostloc.offline`) or the bridge/`onNewIntent` automation breaks.
- **Do not run the original GhostMapX module at the same time** — both hook
  the same system_server location surfaces. GhostMapX is currently disabled
  in the framework DB; re-enable it only if this module is turned off.
- `scripts/device/99ghostloc.sh` is installed as a Magisk `service.d` script.
  It periodically checkpoints the framework config WAL so service-channel
  writes remain visible to the boot-time preference fallback. Re-install it
  after flashing/wipes: copy to `/data/adb/service.d/99ghostloc.sh`, `chmod 755`.

Scope set used on the owned device:

```text
android  system  com.android.phone  com.android.bluetooth
dev.ghostloc.offline  dev.ghostloc.probe  dev.ghostloc.demo
```

## User docs

- [docs/MODULE_USER_GUIDE.md](docs/MODULE_USER_GUIDE.md)

## Traceability

- Work log: [docs/TRACEABILITY.md](docs/TRACEABILITY.md)
- Device report: [docs/DEVICE_TEST_REPORT.md](docs/DEVICE_TEST_REPORT.md)
- Plan: [docs/REPLICATION_PLAN.md](docs/REPLICATION_PLAN.md)
