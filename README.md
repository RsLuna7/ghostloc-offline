# GhostLoc Offline

Clean-room, offline Android location control module for owned test devices.

This project intentionally has no login, no authorization server, no challenge flow,
no native guard, and no copied code or assets from GhostMapX.

## Status

Current state: usable offline MVP with verified LSPosed hook chain on a OnePlus7
test device.

Verified capabilities include:

- map-based coordinate selection
- local Start/Stop and configuration storage
- `LocationManager.sendExtraCommand("ghostloc", ...)` command bridge
- `getLastKnownLocation`, `getCurrentLocation`, and listener callback spoofing
- provider list / provider enabled / best provider spoofing
- mock-location hiding
- Geocoder result spoofing
- Wi-Fi identity and scan-result cleanup
- basic telephony/network cleanup
- test-only `probe-app` verification

This is not a complete 1:1 replacement for the original module yet. The remaining
work is tracked in [docs/REPLICATION_PLAN.md](docs/REPLICATION_PLAN.md).

## Modules

- `app`: Android UI plus libxposed module entry.
- `probe-app`: owned test app used to verify hook output on real devices.
- `vector-api-stubs`: compile-time stubs copied from the local Vector/libxposed lab.

## Build

```powershell
.\gradlew.bat :app:assembleDebug :probe-app:assembleDebug
```

The APKs are written to:

```text
app\build\outputs\apk\debug\app-debug.apk
probe-app\build\outputs\apk\debug\probe-app-debug.apk
```

## Runtime Notes

Install the APK on a rooted test device with a compatible libxposed/LSPosed runtime,
enable the module, select the target scopes, then reboot if the framework requires it.

Core settings are stored in the `settings` preference group and read by the hook layer
through libxposed remote preferences.

## Traceability

- Work log and evidence chain: [docs/TRACEABILITY.md](docs/TRACEABILITY.md)
- Device test report: [docs/DEVICE_TEST_REPORT.md](docs/DEVICE_TEST_REPORT.md)
- Remaining implementation plan: [docs/REPLICATION_PLAN.md](docs/REPLICATION_PLAN.md)

