# GhostLoc Offline

Clean-room, offline Android location control module for owned test devices.

This project intentionally has no login, no authorization server, no challenge flow,
no native guard, and no copied code or assets from GhostMapX.

## Status

Current state: owned-device complete on the validated test path.

Validated environment:

- device: `OnePlus7 / GM1901`
- Android: `SDK 35 / Android 15`
- framework: `LSPosed API 100`

Verified capabilities include:

- map-based coordinate selection and local Start/Stop
- route movement with multi-point route JSON, loop mode, pause/resume, and
  auto-bearing
- favorites with named/grouped JSON import/export
- `LocationManager.sendExtraCommand("ghostloc", ...)` command bridge
- `getLastKnownLocation`, `getCurrentLocation`, and listener callback spoofing
- deep `system_server` location-service hook coverage on the owned ROM
- provider list / provider enabled / best provider spoofing
- mock-location hiding
- Geocoder result spoofing
- Wi-Fi identity and scan-result cleanup
- structured LTE/NR `CellInfo` and `ServiceState` synthesis
- GNSS status and NMEA callback synthesis
- public `fused` provider spoofing
- in-app diagnostics and last probe result display
- owned `probe-app` and owned ordinary `demo-app` real-device verification
- disable-module restore-real-location verification on the owned device

The remaining work is now cross-ROM compatibility and broader third-party app
coverage, not missing owned-device core behavior. Current details are tracked in
[docs/REPLICATION_PLAN.md](docs/REPLICATION_PLAN.md).

## Modules

- `app`: Android UI plus libxposed module entry.
- `probe-app`: owned test app used to verify hook output on real devices.
- `demo-app`: owned ordinary location client used to verify standard app-facing
  behavior without probe-specific reflection.
- `vector-api-stubs`: compile-time stubs copied from the local Vector/libxposed lab.

## Build

```powershell
.\gradlew.bat :app:assembleDebug :probe-app:assembleDebug :demo-app:assembleDebug
```

The APKs are written to:

```text
app\build\outputs\apk\debug\app-debug.apk
probe-app\build\outputs\apk\debug\probe-app-debug.apk
demo-app\build\outputs\apk\debug\demo-app-debug.apk
```

## Runtime Notes

Install the APK on a rooted test device with a compatible libxposed/LSPosed runtime,
enable the module, select the target scopes, then reboot if the framework requires it.

Core settings are stored in the `settings` preference group and read by the hook layer
through libxposed remote preferences.

The current scope set used on the owned validation path includes:

- `android`
- `com.android.phone`
- `com.android.location.fused`
- `com.android.bluetooth`
- `dev.ghostloc.offline`
- `dev.ghostloc.probe`
- `dev.ghostloc.demo`

Optional placeholder scopes remain in metadata for ROMs that expose them:

- `com.oplus.location`
- `com.xiaomi.location.fused`

## User Docs

- User-facing module overview: [docs/MODULE_USER_GUIDE.md](docs/MODULE_USER_GUIDE.md)

## Traceability

- Work log and evidence chain: [docs/TRACEABILITY.md](docs/TRACEABILITY.md)
- Device test report: [docs/DEVICE_TEST_REPORT.md](docs/DEVICE_TEST_REPORT.md)
- Validation and compatibility plan: [docs/REPLICATION_PLAN.md](docs/REPLICATION_PLAN.md)
