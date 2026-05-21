# GhostLoc Offline

Clean-room, offline Android location control module for owned test devices.

This project intentionally has no login, no authorization server, no challenge flow,
no native guard, and no copied code or assets from GhostMapX.

## Modules

- `app`: Android UI plus libxposed module entry.
- `vector-api-stubs`: compile-time stubs copied from the local Vector/libxposed lab.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Runtime Notes

Install the APK on a rooted test device with a compatible libxposed/LSPosed runtime,
enable the module, select the target scopes, then reboot if the framework requires it.

Core settings are stored in the `settings` preference group and read by the hook layer
through libxposed remote preferences.

