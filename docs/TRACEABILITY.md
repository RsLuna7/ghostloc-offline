# GhostLoc Offline Traceability

This document records what was changed, why it was changed, and how each step was
verified. The goal is to make the implementation chain auditable from the reverse
engineering handoff to the current clean-room module.

## Source Boundary

This repository is a clean-room implementation. It does not copy original APK
source, resources, package name, native guard logic, server domains, signing keys,
or authorization protocol.

The implementation is based on behavioral findings from the local analysis
workspace and public Android/libxposed APIs.

## Reverse Engineering Backup

Before creating this project, the analysis workspaces were preserved.

Git commit in the analysis worktree:

```text
44b256e Backup GhostMapX reverse engineering workspace before clean-room rewrite
```

Git bundle backups:

```text
D:\.develop\backups\ghostmapx_analysis-20260522-021150.bundle
D:\.develop\backups\ghostmapx_analysis-agent-20260522-021150.bundle
```

Reference analysis directories:

```text
D:\.develop\ghostmapx_analysis
D:\.develop\ghostmapx_analysis-agent
```

Key reference documents in the analysis worktree:

```text
D:\.develop\ghostmapx_analysis-agent\WORK_SUMMARY.md
D:\.develop\ghostmapx_analysis-agent\CLEAN_ROOM_SPEC.md
D:\.develop\ghostmapx_analysis-agent\FULL_REVERSE_REPORT.md
D:\.develop\ghostmapx_analysis-agent\NATIVE_ANALYSIS.md
```

## Repository Timeline

### 1aca6dd - Initial offline GhostLoc clean-room module

Purpose:

- create a new standalone project at `D:\.develop\ghostloc-offline`
- use the local Vector/libxposed API-100 compatible build baseline
- remove all auth, login, server challenge, and native guard concepts
- provide the first offline module and UI

Main files:

```text
app/src/main/java/dev/ghostloc/offline/MainActivity.java
app/src/main/java/dev/ghostloc/offline/GhostLocModule.java
app/src/main/java/dev/ghostloc/offline/ModuleApplication.java
app/src/main/java/dev/ghostloc/offline/SettingsStore.java
app/src/main/resources/META-INF/xposed/java_init.list
app/src/main/resources/META-INF/xposed/module.prop
app/src/main/resources/META-INF/xposed/scope.list
vector-api-stubs/
```

Implemented:

- map UI with osmdroid
- local coordinate, speed, altitude, accuracy, and bearing controls
- local Start/Stop state
- initial LSPosed module entry
- initial location, mock, Wi-Fi, Bluetooth, and telephony hooks
- buildable debug APK

Build verification:

```powershell
.\gradlew.bat :app:assembleDebug
```

### 2bd916e - Add device probe and expanded offline hooks

Purpose:

- add a controlled owned test app for end-to-end verification
- expand API-surface spoofing before touching private system internals
- prove real-device injection through LSPosed logs

Main files:

```text
probe-app/src/main/java/dev/ghostloc/probe/ProbeActivity.java
probe-app/src/main/AndroidManifest.xml
app/src/main/java/dev/ghostloc/offline/GhostLocModule.java
app/src/main/java/dev/ghostloc/offline/MainActivity.java
```

Implemented:

- `probe-app` reads location, mock status, Wi-Fi, and telephony fields
- `getCurrentLocation` callback handling
- Geocoder spoofing
- more Wi-Fi fields: MAC, BSSID, SSID, RSSI, frequency, link speed
- more telephony fields: network type, phone type, operator, country
- adb automation extras for setting coordinates in `MainActivity`

Real-device evidence:

```text
GhostLocModule: loaded process=dev.ghostloc.probe
GhostLocProbe: location=31.230400,121.473700 speed=3.05 alt=80.0 acc=25.0 mock=false provider=gps
GhostLocProbe: mock_setting=0
GhostLocProbe: wifi_mac=02:00:00:00:00:00 bssid=02:00:00:00:00:00 ssid="GhostLoc" scans=0
GhostLocProbe: network_type=13 roaming=false cells=0
```

### 30eb9d2 - Expand spoofing surface and bridge commands

Purpose:

- close more of the original feature gap
- add a local command bridge that mirrors the original command-oriented shape
- expand provider, Geocoder, Wi-Fi, and telephony coverage

Main files:

```text
app/src/main/java/dev/ghostloc/offline/GhostLocModule.java
app/src/main/java/dev/ghostloc/offline/MainActivity.java
probe-app/src/main/java/dev/ghostloc/probe/ProbeActivity.java
```

Implemented:

- UI calls `LocationManager.sendExtraCommand("ghostloc", ...)`
- hook layer observes command bridge calls
- command names:
  - `start`
  - `stop`
  - `update_location`
  - `move`
  - `put_config`
  - `set_speed`
  - `set_altitude`
  - `set_bearing`
  - `is_start`
- provider APIs:
  - `getProviders`
  - `getAllProviders`
  - `isProviderEnabled`
  - `getBestProvider`
- Geocoder sync and async listener handling
- Wi-Fi fields:
  - `getMacAddress`
  - `getBSSID`
  - `getSSID`
  - `getRssi`
  - `getFrequency`
  - `getLinkSpeed`
  - `getNetworkId`
  - `getSupplicantState`
  - `getScanResults`
- Telephony fields:
  - `getNetworkType`
  - `getDataNetworkType`
  - `getVoiceNetworkType`
  - `getPhoneType`
  - `getNetworkOperator`
  - `getNetworkOperatorName`
  - `getNetworkCountryIso`
  - `isNetworkRoaming`
  - `getAllCellInfo`
  - `getServiceState`

Fix made during testing:

- LSPosed remote preferences are read-only inside hook callbacks on this runtime.
  The command bridge now observes commands in the hook layer and leaves persistent
  writes to the app-side service path.

Real-device evidence:

```text
GhostLocModule: bridge command observed=start
GhostLocApp: Bridge command=start handled=true
GhostLocProbe: current_location=22.319300,114.169400 mock=false
GhostLocProbe: providers=[gps, network, passive, fused] gps_enabled=true best=gps
GhostLocProbe: location=22.319300,114.169400 speed=3.05 alt=80.0 acc=25.0 mock=false provider=gps
GhostLocProbe: geocoder=1
GhostLocProbe: mock_setting=0
GhostLocProbe: wifi_mac=02:00:00:00:00:00 bssid=02:00:00:00:00:00 ssid="GhostLoc" rssi=-55 freq=2412 link=72 network_id=0 supplicant=COMPLETED scans=0
GhostLocProbe: network_type=13 phone_type=1 operator=00101 operator_name=GhostLoc country=us roaming=false cells=0
```

### Phase 1 - Add deep system_server location service hooks

Purpose:

- implement Phase 1 of `docs/REPLICATION_PLAN.md`
- move beyond app-level `LocationManager` hooks into the Android 15
  `system_server` service/binder path
- keep all private API handling defensive: missing classes, missing methods, and
  unsupported signatures log and skip instead of crashing

Main files:

```text
app/src/main/java/dev/ghostloc/offline/GhostLocModule.java
app/src/main/resources/META-INF/xposed/scope.list
probe-app/src/main/java/dev/ghostloc/probe/ProbeActivity.java
docs/DEVICE_TEST_REPORT.md
docs/REPLICATION_PLAN.md
```

Static ROM inspection:

- pulled `/system/framework/services.jar` and `/system/framework/framework.jar`
  from the OnePlus7 test device
- used Android SDK `dexdump` to inspect the Android 15 service signatures
- confirmed `com.android.server.location.LocationManagerService` extends
  `android.location.ILocationManager$Stub`
- observed Android 15 service signatures including:
  - `LocationManagerService#getLastLocation(String, LastLocationRequest, String, String): Location`
  - `LocationManagerService#getCurrentLocation(String, LocationRequest, ILocationCallback, String, String, String): ICancellationSignal`
  - `LocationManagerService#registerLocationListener(String, LocationRequest, ILocationListener, String, String, String): void`
  - `LocationProviderManager#getLastLocation(LastLocationRequest, CallerIdentity, int): Location`
  - `LocationProviderManager#getCurrentLocation(LocationRequest, CallerIdentity, int, ILocationCallback): ICancellationSignal`
  - `AbstractLocationProvider#reportLocation(LocationResult): void`
  - `GnssNative#reportLocation(boolean, Location): void`

Implemented:

- deep hook installer for:
  - `com.android.server.location.LocationManagerService`
  - `android.location.ILocationManager$Stub`
  - `com.android.server.location.provider.LocationProviderManager`
  - `com.android.server.location.provider.AbstractLocationProvider`
  - `com.android.server.location.gnss.hal.GnssNative`
- reflection-based method discovery for target service classes
- defensive one-time method hook registration keyed by runtime signatures
- service return spoofing for `getLastLocation`
- callback spoofing for `getCurrentLocation`, `requestLocationUpdates`, and
  `registerLocationListener`
- `ILocationManager$Stub#onTransact` binder-entry trace logging
- inbound `reportLocation` replacement for `Location` and best-effort
  `LocationResult` factory paths
- probe-app logging split into:
  - `public_last_location`
  - `public_current_location`
  - `public_listener_location`
  - `binder_service`
  - `binder_reflection_error`
- module metadata scope now includes `dev.ghostloc.probe`

Real-device evidence:

```text
GhostLocModule: service trace uid=10500 android.location.ILocationManager$Stub#onTransact(int,android.os.Parcel,android.os.Parcel,int):boolean
GhostLocModule: service current com.android.server.location.provider.LocationProviderManager#getCurrentLocation(android.location.LocationRequest,android.location.util.identity.CallerIdentity,int,android.location.ILocationCallback):android.os.ICancellationSignal
GhostLocModule: service current com.android.server.location.LocationManagerService#getCurrentLocation(java.lang.String,android.location.LocationRequest,android.location.ILocationCallback,java.lang.String,java.lang.String,java.lang.String):android.os.ICancellationSignal
GhostLocProbe: public_last_location=35.689500,139.691700 mock=false provider=gps
GhostLocProbe: public_current_location=35.689500,139.691700 mock=false
GhostLocProbe: public_listener_location=35.689500,139.691700 mock=false provider=gps
GhostLocProbe: binder_service=android.location.ILocationManager$Stub$Proxy
GhostLocProbe: location=35.689500,139.691700 speed=3.05 alt=80.0 acc=25.0 mock=false provider=gps
```

Remaining Phase 1 limits found:

- direct app-side reflection invocation of hidden
  `ILocationManager#getLastLocation` is blocked by Android 15 hidden API method
  filtering and currently logs `binder_reflection_error=NoSuchMethodException`
- the service/binder path itself is verified through system_server
  `ILocationManager$Stub#onTransact`, `LocationManagerService`, and
  `LocationProviderManager` hook-hit logs
- a separate third-party demo app and disable/restore behavior check are still
  useful follow-up acceptance tests

## Current Build Artifacts

```text
D:\.develop\ghostloc-offline\app\build\outputs\apk\debug\app-debug.apk
D:\.develop\ghostloc-offline\probe-app\build\outputs\apk\debug\probe-app-debug.apk
```

## Current Known Limits

The current module is verified against public Android API surfaces and the owned
probe app. It now has OnePlus7 / Android 15 service-layer hooks, but it is not
yet a complete original-equivalent module because broader private and vendor
surfaces still need per-ROM implementation and verification.

Known gaps:

- app-side direct hidden `ILocationManager` reflection is filtered on Android 15
- broader third-party app service-path compatibility checks
- vendor fused-location implementations
- structured non-empty `CellInfo` synthesis
- GNSS, NMEA, AGPS, and satellite status synthesis
- route-management UI equivalent to a polished product
- broader Android/ROM compatibility matrix
