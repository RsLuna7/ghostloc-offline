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

### Phase 3 - Add structured CellInfo synthesis

Purpose:

- implement Phase 3 of `docs/REPLICATION_PLAN.md`
- replace the previous empty `getAllCellInfo()` result with deterministic
  synthetic LTE/NR state
- keep telephony spoofing internally consistent across operator, country,
  `ServiceState`, `getAllCellInfo()`, and `requestCellInfoUpdate()`

Main files:

```text
app/src/main/java/dev/ghostloc/offline/GhostLocModule.java
app/src/main/java/dev/ghostloc/offline/SettingsStore.java
probe-app/src/main/java/dev/ghostloc/probe/ProbeActivity.java
docs/DEVICE_TEST_REPORT.md
docs/REPLICATION_PLAN.md
```

Implemented:

- `CellProfile` synthesis derived deterministically from the selected coordinate
- optional telephony override storage keys:
  - `cell_mcc`
  - `cell_mnc`
  - `cell_country_iso`
  - `cell_operator_name`
  - `cell_enable_nr`
- coordinate-to-carrier preset mapping for:
  - Japan
  - China
  - Korea
  - UK
  - US
  - `GhostLoc` fallback
- reflected construction and field population for:
  - `CellInfoLte`
  - `CellIdentityLte`
  - `CellSignalStrengthLte`
  - `CellInfoNr`
  - `CellIdentityNr`
  - `CellSignalStrengthNr`
- defensive base-cell metadata population through hidden
  `setRegistered` / `setTimeStamp` / `setCellConnectionStatus` with direct-field
  fallback
- `requestCellInfoUpdate(Executor, CellInfoCallback)` callback interception and
  synthetic callback delivery
- `ServiceState` consistency fill for:
  - operator alpha/numeric
  - voice/data registration state
  - `NetworkRegistrationInfo` voice/data entries
- probe-app structured telephony output for:
  - LTE cell identity/signal
  - NR cell identity/signal
  - `ServiceState`
  - `requestCellInfoUpdate()` callback

Real-device evidence:

```text
GhostLocProbe: public_cells=LTE reg=true status=1 mcc=440 mnc=10 ci=156030181 pci=244 tac=46053 earfcn=1773 rsrp=-99 rsrq=-8 rssnr=148 ta=15 | NR reg=false status=2 mcc=440 mnc=10 nci=12021270607 pci=414 tac=6401 nrarfcn=641682 ssRsrp=-102 ssRsrq=-13 ssSinr=163
GhostLocProbe: public_service_state=state=0 roaming=false operator=44010 alpha=NTT DOCOMO regs=2
GhostLocModule: cell callback android.telephony.TelephonyManager#requestCellInfoUpdate(java.util.concurrent.Executor,android.telephony.TelephonyManager$CellInfoCallback):void
GhostLocProbe: network_type=20 data_type=20 voice_type=13 phone_type=1 operator=44010 operator_name=NTT DOCOMO country=jp roaming=false service_numeric=44010 service_alpha=NTT DOCOMO cells=2
GhostLocProbe: cellinfo_callback=LTE reg=true status=1 mcc=440 mnc=10 ci=156030181 pci=244 tac=46053 earfcn=1773 rsrp=-99 rsrq=-8 rssnr=148 ta=15 | NR reg=false status=2 mcc=440 mnc=10 nci=12021270607 pci=414 tac=6401 nrarfcn=641682 ssRsrp=-102 ssRsrq=-13 ssSinr=163
```

Phase 3 notes:

- Android 15 public SDK stubs do not expose the hidden constructors and fields
  needed for `CellInfo` synthesis, so the implementation relies on runtime
  reflection and fails closed when signatures move
- on the OnePlus7 Android 15 test device, the no-arg constructor plus field
  population path was sufficient; Parcel-based fallback is still not needed
  there
- the telephony profile is now internally consistent for the owned probe path,
  but broad third-party app compatibility is still a later acceptance task

### Phase 4 - Add GNSS status and NMEA synthesis

Purpose:

- implement Phase 4 of `docs/REPLICATION_PLAN.md`
- make the spoofed coordinate visible through GNSS callback paths instead of
  only basic location APIs
- generate stable NMEA output that matches the current spoof state

Main files:

```text
app/src/main/java/dev/ghostloc/offline/GhostLocModule.java
app/src/main/java/dev/ghostloc/offline/SettingsStore.java
probe-app/src/main/java/dev/ghostloc/probe/ProbeActivity.java
docs/DEVICE_TEST_REPORT.md
docs/REPLICATION_PLAN.md
```

Implemented:

- GNSS settings storage keys:
  - `gnss_sat_count`
  - `nmea_enabled`
  - `agps_enabled`
- synthetic satellite profile generation from the current spoof coordinate
- reflected `GnssStatus` construction with populated satellite arrays:
  - SVID/flags
  - CN0
  - elevation
  - azimuth
  - carrier frequency
  - baseband CN0
- public callback hook coverage for:
  - `LocationManager.registerGnssStatusCallback(...)`
  - `LocationManager.addGpsStatusListener(...)`
  - `LocationManager.addNmeaListener(...)`
  - `LocationManager.getGpsStatus(...)`
- one-shot synthetic GNSS callback delivery:
  - `onStarted`
  - `onFirstFix`
  - `onSatelliteStatusChanged`
- deterministic NMEA generation for:
  - `$GPGGA`
  - `$GPRMC`
  - `$GPGSA`
  - `$GPGSV`
- probe-app GNSS verification for:
  - `GnssStatus.Callback`
  - legacy `GpsStatus.Listener`
  - `OnNmeaMessageListener`

Real-device evidence:

```text
GhostLocProbe: gnss_started
GhostLocProbe: gnss_first_fix=1100
GhostLocProbe: nmea=$GPGGA,032329.00,3541.3700,N,13941.5020,E,1,12,0.9,80.0,M,0.0,M,,*69
GhostLocProbe: nmea=$GPRMC,032329.00,A,3541.3700,N,13941.5020,E,5.9,0.0,220526,,*39
GhostLocProbe: nmea=$GPGSA,A,3,03,07,09,16,19,22,26,28,31,14,18,1.2,0.9,1.5*12
GhostLocProbe: nmea=$GPGSV,3,1,12,18,36,265,42,18,80,035,30,17,79,071,31,20,61,319,30*76
GhostLocProbe: gps_status_event=1 snapshot=null
GhostLocProbe: gps_status_event=3 snapshot=null
GhostLocProbe: gps_status_event=4 snapshot=null
```

Phase 4 notes:

- on the OnePlus7 Android 15 path, `GnssStatus.Callback` and NMEA listener
  spoofing are working and sufficient for the owned probe acceptance criteria
- legacy `GpsStatus.Listener` receives synthetic event codes, but
  `LocationManager.getGpsStatus(null)` still reports `null` on this ROM and
  should be treated as a compatibility follow-up rather than a blocker
- the implementation is defensive: missing GNSS fields or callback methods
  degrade quietly instead of crashing the target process

### Phase 2 - Add fused-provider allowlist hooks

Purpose:

- start Phase 2 of `docs/REPLICATION_PLAN.md`
- add ROM-bounded fused-provider discovery and hook points without broad unsafe
  class scanning
- verify the public `fused` provider path on the OnePlus7 ROM

Main files:

```text
app/src/main/java/dev/ghostloc/offline/GhostLocModule.java
app/src/main/java/dev/ghostloc/offline/SettingsStore.java
app/src/main/resources/META-INF/xposed/scope.list
probe-app/src/main/java/dev/ghostloc/probe/ProbeActivity.java
docs/DEVICE_TEST_REPORT.md
docs/REPLICATION_PLAN.md
```

ROM findings:

- the current device exposes package `com.android.location.fused`
- `dumpsys package com.android.location.fused` reports:
  - service action `com.android.location.service.FusedLocationProvider`
  - component `com.android.location.fused/.FusedLocationService`
  - GNSS overlay component
    `com.android.location.fused/com.android.location.gnss.GnssOverlayLocationService`
- static APK inspection shows class
  `com.android.location.fused.FusedLocationProvider`
  extending `android.location.provider.LocationProviderBase`
- the OnePlus7 ROM does not currently expose installed packages
  `com.oplus.location` or `com.xiaomi.location.fused`

Implemented:

- vendor/fused package allowlist:
  - `com.android.location.fused`
  - `com.oplus.location`
  - `com.xiaomi.location.fused`
- defensive runtime flag `vendor_hooks_enabled`
- scope metadata expanded to include the Oplus/Xiaomi vendor package names
- fused allowlist hook attempts for:
  - `com.android.location.fused.FusedLocationProvider`
  - `com.android.location.fused.FusedLocationProvider$ChildLocationListener`
  - placeholder vendor class names for Oplus/Xiaomi with missing-class skip logs
- fused public probe coverage:
  - `fused_last_location`
  - `fused_current_location`

Real-device evidence:

```text
GhostLocProbe: fused_last_location=35.689500,139.691700 mock=false provider=fused
GhostLocProbe: fused_current_location=35.689500,139.691700 mock=false provider=fused
```

Phase 2 notes:

- the owned probe now verifies the public `fused` provider path on-device
- `dumpsys location` on the owned device shows the active fused target as
  `com.google.android.gms/com.google.android.location.fused.FusedLocationService`
  rather than a standalone `com.android.location.fused` process
- no `com.android.location.fused` PID was present during active fused requests
  on this ROM, so there is no additional standalone process evidence to claim
  or chase on the owned path
- non-present `com.oplus.location` and `com.xiaomi.location.fused` paths stay
  defensive and skipped

### Phase 5 - Add product control surface and probe diagnostics

Purpose:

- close the remaining user-facing gap with route control, diagnostics, and
  in-app probe visibility
- keep all previously verified hook paths intact while extending only the
  control plane

Main files:

```text
app/src/main/java/dev/ghostloc/offline/MainActivity.java
app/src/main/java/dev/ghostloc/offline/GhostLocModule.java
app/src/main/java/dev/ghostloc/offline/ProbeReportReceiver.java
app/src/main/java/dev/ghostloc/offline/SettingsStore.java
app/src/main/AndroidManifest.xml
probe-app/src/main/java/dev/ghostloc/probe/ProbeActivity.java
docs/DEVICE_TEST_REPORT.md
docs/REPLICATION_PLAN.md
```

Implemented:

- rebuilt `MainActivity` into a sectioned operator surface for:
  - coordinates and spoof basics
  - Wi-Fi / cell / mock / vendor hook policy toggles
  - radio and GNSS tuning
  - multi-point route JSON editing
  - named/grouped favorites JSON editing
  - diagnostics and reset controls
- route controls:
  - multi-point route storage in `route_points`
  - loop mode
  - pause/resume
  - auto-bearing
  - speed variation
- favorites controls:
  - `name` / `group` / `lat` / `lon` JSON model
  - legacy semicolon format migration on read
  - JSON import/export through the app surface
- module-side route execution:
  - polyline interpolation
  - route distance accumulation
  - loop wrapping
  - speed variation
  - altitude and accuracy jitter
- diagnostics buffers:
  - `diag_recent_hits`
  - `diag_recent_errors`
  - `diag_last_process`
- in-app probe result visibility:
  - `probe-app` now broadcasts its final report and pass/fail verdict
  - `ProbeReportReceiver` stores `last_probe_report` / `last_probe_ok` /
    `last_probe_at`
  - `MainActivity` renders the last probe verdict and report in diagnostics
- owned automation helper:
  - `MainActivity` accepts base64 route/favorites extras for non-interactive
    device validation without changing the normal manual flow

Real-device evidence:

```text
GhostLocApp: service bound framework=LSPosed api=100
GhostLocApp: bridge command=start handled=true
GhostLocProbe: public_last_location=35.689540,139.691766 mock=false provider=gps
GhostLocProbe: location=35.689540,139.691766 speed=3.13 alt=82.9 acc=25.5 mock=false provider=gps
GhostLocProbe: probe_status=PASS
GhostLocApp: probe report stored ok=true
```

Phase 5 notes:

- the spoof output drifted from the base coordinate `35.689500,139.691700` to
  `35.689540,139.691766`, confirming that route movement stayed active
- speed, altitude, and accuracy moved away from their base values
  (`3.05 / 80.0 / 25.0`) to `3.13 / 82.9 / 25.5`, confirming the new variation
  and jitter controls were live in the hook path
- after a full device reboot, the owned probe still reported the Tokyo/Japan
  profile and the app stored a fresh `last_probe_report`, confirming reboot
  persistence on the current device path

### Validation closure - ordinary demo and restore-real-location

Purpose:

- close the last Phase 1 acceptance gaps on the owned device

Main files:

```text
demo-app/src/main/java/dev/ghostloc/demo/MainActivity.java
app/src/main/resources/META-INF/xposed/scope.list
docs/DEVICE_TEST_REPORT.md
docs/REPLICATION_PLAN.md
```

Implemented:

- added a second owned ordinary location client app:
  - package `dev.ghostloc.demo`
  - no binder reflection
  - no module service dependency
  - standard `LocationManager` last/current/listener reads only
- expanded scope metadata to include `dev.ghostloc.demo`
- performed a disable-module validation by uninstalling `dev.ghostloc.offline`,
  rebooting the device, and rerunning both `demo-app` and `probe-app`
- restored the module by reinserting the LSPosed module/scope records, rebooting,
  and rerunning the active spoof checks

Real-device evidence:

```text
GhostLocDemo: gps_last=35.690985,139.694028 speed=2.52 alt=82.6 acc=25.1 provider=gps mock=false
GhostLocDemo: fused_last=35.690985,139.694028 speed=2.52 alt=82.6 acc=25.1 provider=fused mock=false
GhostLocProbe: location=null
GhostLocProbe: network_type=0 data_type=0 voice_type=0 phone_type=2 operator= operator_name= country= roaming=false service_numeric=null service_alpha=null cells=0
GhostLocDemo: gps_last=35.689573,139.691819 speed=3.19 alt=82.8 acc=26.0 provider=gps mock=false
GhostLocProbe: probe_status=PASS
```

Closure notes:

- the ordinary demo app received the spoofed Tokyo route output while the module
  was active, closing the "second ordinary app" acceptance gap
- after uninstalling the module and rebooting, both owned clients returned to
  non-spoofed behavior on this device path:
  - `demo-app` saw `gps_last=null` / `fused_last=null`
  - `probe-app` saw `location=null`, empty synthetic cell state, and
    `probe_status=FAIL`
- after restoring the LSPosed module registration and rebooting, the module
  resumed normal spoof behavior for both `demo-app` and `probe-app`

## Current Build Artifacts

```text
D:\.develop\ghostloc-offline\app\build\outputs\apk\debug\app-debug.apk
D:\.develop\ghostloc-offline\probe-app\build\outputs\apk\debug\probe-app-debug.apk
D:\.develop\ghostloc-offline\demo-app\build\outputs\apk\debug\demo-app-debug.apk
```

## Current Known Limits

The current module is verified against public Android API surfaces, the owned
probe app, and the owned ordinary demo app. It now has OnePlus7 / Android 15
service-layer hooks and owned-device validation closure. Remaining limits are
cross-ROM rather than owned-device core behavior.

Known gaps:

- app-side direct hidden `ILocationManager` reflection is filtered on Android 15
- legacy `LocationManager.getGpsStatus(null)` snapshot still returns `null` on
  the current ROM even though the GNSS/NMEA callback paths are spoofed
- broader Android/ROM compatibility matrix

## 2026-08-16 — Kotlin rewrite regression, live-sync architecture, Material 3 UI

### Real-device regression (Kotlin rewrite)

- module loading, service bind (`LSPosed api=100`), system_server deep hook
  hits all verified on device after the rewrite
- probe PASS with Tokyo route profile; demo-app received spoofed output with
  movement drift and jitter; stop restored real behavior (probe FAIL/null)
- map tab interactions verified: long-press pick with GCJ-02 correction fed
  the params tab (35.700164,139.682203 observed after a map long-press)
- UI inset fix: Android 15 enforced edge-to-edge put the status text under
  the system bar and the bottom buttons under the gesture area; fixed with an
  explicit WindowInsets listener on the root layout (t=79 verified by pixels;
  uiautomator dumps were stale and misleading during diagnosis)

### Root cause of "started but position unchanged"

Three independent faults stacked:

1. The LSPosed/Vector scope table lacked the module's own package, so the
   module never injected into the app process and the command bridge never
   fired ("bridge command sent" only meant the reflection invoke returned).
2. On Vector, hook-side remote preferences are read-only, and UI-side service
   preference writes land in the config WAL which the daemon never reads
   (daemon loads the main database once at boot). Every UI write was
   therefore invisible to hooks; the engine kept serving the last
   checkpointed state.
3. Module APK reinstalls change `/data/app` paths while the framework keeps
   injecting the path recorded at boot, so reinstalls without a reboot (and
   `modules.apk_path` refresh) silently pinned old code.

### Live-sync architecture

- Every UI commit now writes the local `settings` xml and sends a full `sync`
  command through `LocationManager.sendExtraCommand`.
- `LocationManagerService#sendExtraCommand` is hooked in system_server:
  `Bridge.handle` persists the state to `/data/misc/ghostloc/state.properties`
  (system_server-only access) and skips the original call.
- system_server location hooks answer all apps from that file; client-side
  location spoofing was removed in favor of binder answers.
- Verified live switching without reboot: Wuhan 30.5728/114.3051 → Beijing
  39.9042/116.4074 → Shenzhen 22.5431/114.0579, observed immediately in both
  probe-app and unscoped demo-app.
- Known gap: `getCurrentLocation` returns null while spoofing (apps fall back
  to `getLastKnownLocation`/listeners); client-only hooks (GNSS/NMEA/Wi-Fi/
  cell settings) apply after target-app restart.

### Device-side state changes (validation device)

- scope additions: `dev.ghostloc.offline`, restore `dev.ghostloc.demo`
- GhostMapX module disabled in the framework DB (conflicts with this module)
- `scripts/device/99ghostloc.sh` installed as a Magisk `service.d` script:
  periodic `PRAGMA wal_checkpoint(PASSIVE)` on the framework config DB so
  service-channel writes stay visible to the boot-time preference fallback

### Material 3 UI refresh (0.3.0)

- teal Material 3 theme, card sections, Material switches, styled button
  hierarchy (filled 开始 / outlined 停止 / text secondary), collapsible
  advanced settings, adaptive launcher icon
- insets fix retained; four-tab structure unchanged; pixel-verified rendering
  on device (header band #93E9D6 across all tabs)

### Map tab interaction pass (evening)

Reported pain: map panning felt rough and horizontal drags easily flipped the
pager to the params tab. Three stacked causes, all addressed:

1. `ViewPager2`'s inner RecyclerView intercepted map drags past touch slop —
   the map froze mid-gesture and the page slid away. New `MapTouchWrapper`
   (FrameLayout around the MapView) claims the parent gesture chain for any
   touch starting on the map (`requestDisallowInterceptTouchEvent`).
2. `setTilesScaledToDpi(true)` requested lower-zoom tiles and upscaled them —
   blurry and chunky while panning on the 1080p panel. Disabled; fling
   explicitly enabled.
3. Every draft sync removed/re-added the marker and forced `setCenter`, so
   the viewport jumped back to the target on any background refresh. The
   marker is now a single draggable instance (drag end feeds `pickPoint`
   through the same GCJ-02 correction path), syncs only update its position,
   and recentering is explicit.

Map tab layout rebuilt as a full-bleed map with floating controls: top card
(search + tile source), right-side zoom in/out/recenter icon buttons, single
bottom card (pickup-correction switch, hint, start/stop/save). Search input
now also submits on IME action.

On-device verification (same evening, OnePlus7, after `adb install -r` +
app restart, no reboot needed for the UI side):

- layout pixel-checked: search card / right button column / bottom card in
  place, no overlap or truncation; AMap tiles loading (岔河集乡 area)
- three fast horizontal flings on the map (`input swipe … 120-200 ms`) left
  the pager on the map tab (uiautomator still reports `searchCard` /
  `pickupCorrectCheck`); before the wrapper these flipped to the params tab
- panning persisted (viewport stayed where dragged, no snap-back to the
  marker), zoom in ×2 and recenter buttons verified visually
- long-press place-and-start fired `bridge sync sent active=true`; marker
  title showed corrected WGS coordinates — note: this test overwrote the
  active target with 39.103410,116.414553 (near the previous target)
- `svc power stayon usb` used during testing to keep the panel awake for
  screenshots; restored afterwards

## 2026-08-16 (late) — QQ shared-location leak: server-side Wi-Fi/cell masking

Reported symptom: AMap displayed the spoofed position, but QQ's
location sharing showed the real one. Comparison against the original
GhostMapX decompilation (`D:\.develop\ghostmapx_analysis-agent`) found the
design gap: GhostMapX never hooks third-party apps — QQ is not in its scope
either. Instead it masks location inputs **at the system-service side**, so
every app regardless of scope loses its alternative positioning channels:

- `GhostLocationHook.handlePackage` returns early for unknown packages
  (jadx: GhostLocationHook.java:54-131) — no QQ hooks existed at all
- Wi-Fi: `Hd.s` resolves the WifiStack APEX classloader
  (`SystemServerClassLoaderFactory.sLoadedPaths`, key `service-wifi.jar`) and
  `Hd.r` hooks `com.android.server.wifi.WifiServiceImpl`
  (`getScanResults`→empty incl. `ParceledListSlice`, `getConnectionInfo`→
  masked WifiInfo, `startScan`→false, `registerScanResultsCallback`→
  suppressed, `getMatchingScanResults`→empty)
- Cell: PhoneInterfaceManager hooks in com.android.phone plus
  TelephonyRegistry `notifyCellInfo*` arg replacement in system_server

ghostloc-offline only had the client-side equivalents (WifiManager /
TelephonyManager class hooks), which do not run in out-of-scope processes —
QQ's Tencent location SDK scanned real BSSIDs in its own process and
re-derived the true position from its server-side database.

Changes (all server-side, scope-independent):

- `hooks/WifiServiceHooks.kt` (new): WifiServiceImpl masking in
  system_server, APEX classloader resolution with a
  `SystemServiceManager#loadClassFromLoader` fallback watcher
- `hooks/PhoneServiceHooks.kt` (new): PhoneInterfaceManager synthesis in
  com.android.phone (getAllCellInfo / getCellLocation / requestCellInfoUpdate
  / network types / service state / operators)
- `DeepServiceHooks`: TelephonyRegistry added as a target with
  `notifyCellInfo(ForSubscriber)` list replacement; `getCurrentLocation`
  dispatch now also handles the binder shape
  `ILocationListener.onLocationChanged(List<Location>, IRemoteCallback)` —
  the previous single-arg attempts never matched it, which is why
  `getCurrentLocation` returned null while spoofing

Verified on device after `adb install -r` + reboot:

- bridge sync handled by the new system_server code (`handled=sync keys=28`)
- unscoped AMap calls hit the server-side masks:
  `wifi startScan masked WifiServiceImpl#startScan` (system_server pid),
  `phone cell location masked PhoneInterfaceManager#getCellLocation`
  (com.android.phone pid)
- probe PASS: spoofed location on listener path, `scans=0`, masked
  Wi-Fi identity, synthetic LTE+NR cells, `mock=false`
- no hook errors or skipped deep hooks in logcat
- QQ itself must be spot-checked by hand (only the owner can drive its UI):
  the expectation is that with its Wi-Fi/cell channels empty and
  LocationManager answers spoofed server-side, the shared location now
  follows the target

## 2026-08-16 (night) — full mechanism comparison against GhostMapX

Systematic diff of the original module's complete hook inventory (recovered
from the jadx decompilation, with bytecode recovery for methods jadx could
not decompile) against ghostloc-offline, written up as
[docs/GHOSTMAPX_COMPARISON.md](GHOSTMAPX_COMPARISON.md). Notable recovered
details: the original hooks `Location#writeToParcel`/`LocationResult` as a
serialization-level fallback layer, blocks `GnssManagerService`
measurement/antenna listeners, suppresses the whole BLE scan chain inside
com.android.bluetooth, locks down the test-provider channel, and adds
per-output position micro-jitter (±accuracy/15 m) plus GNSS-look extras on
every injected Location. Those are organized into a P0/P1/P2 port backlog;
Wi-Fi service-side masking from the previous entry brought the main
confrontation surfaces to parity already.

## 2026-08-16 (night) — P0 port: serialization fallback, GNSS/BLE reject, test provider

Purpose: close the five P0 gaps listed in
[docs/GHOSTMAPX_COMPARISON.md](GHOSTMAPX_COMPARISON.md) §6 so any app,
scoped or not, sees a consistent spoofed radio/location picture.

Main files:

```text
app/src/main/java/dev/ghostloc/offline/hooks/BasicLocationHooks.kt
app/src/main/java/dev/ghostloc/offline/hooks/BleServiceHooks.kt
app/src/main/java/dev/ghostloc/offline/hooks/DeepServiceHooks.kt
app/src/main/java/dev/ghostloc/offline/hooks/HookSupport.kt
app/src/main/java/dev/ghostloc/offline/GhostLocModule.kt
app/src/main/java/dev/ghostloc/offline/core/SpoofState.kt
app/src/main/java/dev/ghostloc/offline/core/ModuleRuntime.kt
probe-app/src/main/java/dev/ghostloc/probe/ProbeActivity.java
```

Implemented:

- `Location#writeToParcel` field overwrite + `Location#set` remapping +
  `LocationResult` asList / writeToParcel / Location in-out methods
  (`BasicLocationHooks`, system_server only)
- `GnssManagerService#addGnssMeasurementsListener` /
  `#addGnssAntennaInfoListener` rejected; LMS `#registerGnssStatusCallback`
  forced true
- BLE scan swallow in `com.android.bluetooth` (candidate class list;
  missing classes skipped)
- test-provider family + `setExtraLocationControllerPackageEnabled=false`
- LMS `isProviderEnabled(ForUser)` forces gps/network true
- `ghostloc_faked` extras mark on every `SpoofState.makeLocation` so the
  parcel layer does not rewrite an already-spoofed object
- probe reports `gnss_meas_registered` and `test_provider_present`

Deviations from the original (logged, not silent):

- boolean service methods skip with `false`, not `null` (avoids unbox NPE)
- no original `s` switch that can pass through raw GNSS measurements
- no `"ghostmapx"` provider and no fused enable/disable switch
- BLE class names taken from the comparison table + AOSP aliases;
  `L7.i` itself did not decompile
- timestamp preservation only on the parcel overwrite path (P2 full
  continuity is still open)

Build verification:

```powershell
.\gradlew.bat :app:assembleDebug :probe-app:assembleDebug
```

Device verification: **not done in this step**. Needs `adb install -r` +
reboot so system_server / bluetooth pick up the new hooks, then probe +
`adb logcat -s GhostLocModule` looking for:

- `basic location hooks installed`
- `LocationResult hooks installed`
- `service rejected` / `service forced true` / `provider enabled forced`
- `ble service hooks installed` / `ble scan swallowed`
- probe `gnss_meas_registered=false` and `test_provider_present=false`
  while spoofing is active

## 2026-08-16 (deep night) — P0 device verification, P1 output realism, getCurrentLocation fix

Review of the P0 port against the original decompilation found one gap that
had to close before P1: the original's central rewrite function
(`AbstractC0630px.d`, jadx AbstractC0630px.java:93-95) short-circuits on the
`ghostmapx_faked` extras mark, but the new `remap()` had no such guard —
harmless while output was deterministic, but exactly the bug that would stack
jitter once P1 micro-drift landed. `overwrite()` also dropped the original
fix's extras instead of preserving them like the original.

Changes:

- `BasicLocationHooks.remap`: already-marked inputs pass through untouched
  (guard moved to the entry point, mirroring `d()`'s architecture); source
  extras are merged under the synthesized keys so real-fix extras survive a
  rewrite
- `SpoofState.makeLocation`: per-output micro-drift ported with the exact
  original semantics — uniform `0..accuracy/15` meters along `bearing ±45°`
  (random side, spherical meter→degree conversion via `GeoMath.moveDistance`),
  plus `satellites`(8–45)/`maxCn0`(30–50)/`meanCn0`(20–30) random extras and
  reflective `makeComplete()` (silent skip, same defensive shape as the
  original's `XposedHelpers.callMethod` call)
- `DeepServiceHooks.tryDispatchBinderLocationList`: generalized the binder
  callback dispatch to accept `LocationResult` payloads. The previous entry's
  claim that the `List`-shape fix repaired `getCurrentLocation` was wrong:
  `getCurrentLocation` delivers through `ILocationCallback.onLocationChanged(
  LocationResult, IRemoteCallback)`, whose first parameter is a
  `LocationResult`, not a `List` — the List shape only serves the
  `registerLocationListener` path. Round-1 probe confirmed
  `public_current_location` still null; after the fix both gps and fused
  `getCurrentLocation` return spoofed coordinates
- probe-app: `probeJitter` samples `getLastKnownLocation` ×6 and reports
  spread + satellites-extras visibility (log-only, does not affect PASS)

Device verification (two install/reboot cycles, OnePlus7 / Android 15):

- install counts from LSPosed module log (`/data/adb/lspd/log/modules_*.log`):
  `basic location hooks installed=2` (Location writeToParcel + set),
  `LocationResult hooks installed=4`, LMS deep hooks 14,
  `provider enabled forced` hit on `isProviderEnabledForUser`
- behavioral hits: `service rejected` on `addGnssMeasurementsListener` /
  `addTestProvider` / `removeTestProvider`; `location parcel rewritten` on
  `Location#writeToParcel(Parcel,int)`
- probe: `probe_status=PASS`, `jitter_samples=6 spread=1.31–1.43m
  satellites_extras=6/6` (accuracy ≈22.5 ⇒ max drift 1.5 m, spread within
  expected 2×radius bound), listener path spoofed with visible per-output
  drift, `test_provider_present=false`
- `gnss_meas_registered=true` in the probe report is expected: the hooked
  LMS method returns void, so the neutral skip cannot surface as `false` to
  the client API; the server-side rejection hit log is the actual evidence,
  and no measurement callbacks are ever delivered
- BLE: bluetooth was enabled temporarily (`svc bluetooth enable`) to start
  `com.android.bluetooth`; module log shows `ble service hooks installed=8`:
  `com.android.bluetooth.le_scan.ScanManager#startScan` ×1 (the AOSP-15
  location of ScanManager — the original `gatt.ScanManager` path is missing
  on this ROM), `IScannerCallback$Stub$Proxy` delivery ×3,
  `BluetoothLeScanner#startScan` ×4. `GattService`/`GattNativeInterface`
  matched 0 methods (method names drifted); scan entry + result delivery
  are covered. Bluetooth restored to off afterwards
- `getCurrentLocation` after the LocationResult dispatch fix:
  `public_current_location=50.249030,31.720022` and
  `fused_current_location=50.249022,31.720006 provider=fused` (both were
  null before)

Note on evidence capture: boot-time logcat rotates out before adb is up, so
install counts were read from the LSPosed persistent module log via root
(`su -c grep ... /data/adb/lspd/log/modules_*.log`), not logcat.
