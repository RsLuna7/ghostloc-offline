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
- the ROM still did not surface a stable standalone
  `com.android.location.fused` process with module load logs during this pass,
  so dedicated fused-process hook hits remain a follow-up rather than a claimed
  closed item
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
- dedicated fused-process hook-hit evidence is still missing on the current ROM
- legacy `LocationManager.getGpsStatus(null)` snapshot still returns `null` on
  the current ROM even though the GNSS/NMEA callback paths are spoofed
- broader Android/ROM compatibility matrix
