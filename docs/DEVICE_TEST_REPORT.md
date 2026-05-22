# Device Test Report

## Device

```text
Model: OnePlus7 / GM1901
Device ID: 87d5ddd6
Android SDK: 35
Framework: LSPosed API 100
```

## Installed Packages

```text
dev.ghostloc.offline
dev.ghostloc.probe
```

## LSPosed Scope

The LSPosed config database showed module `dev.ghostloc.offline` enabled as
module id `142`.

Verified scopes:

```text
system
com.android.phone
com.android.bluetooth
com.android.location.fused
dev.ghostloc.offline
dev.ghostloc.probe
```

The scope was inspected and updated with:

```powershell
@'
update modules set enabled=1 where module_pkg_name='dev.ghostloc.offline';
insert or ignore into scope(mid, app_pkg_name, user_id) values(142, 'system', 0);
insert or ignore into scope(mid, app_pkg_name, user_id) values(142, 'com.android.phone', 0);
insert or ignore into scope(mid, app_pkg_name, user_id) values(142, 'com.android.bluetooth', 0);
insert or ignore into scope(mid, app_pkg_name, user_id) values(142, 'com.android.location.fused', 0);
insert or ignore into scope(mid, app_pkg_name, user_id) values(142, 'dev.ghostloc.offline', 0);
insert or ignore into scope(mid, app_pkg_name, user_id) values(142, 'dev.ghostloc.probe', 0);
'@ | adb shell su -c "sqlite3 /data/adb/lspd/config/modules_config.db"
```

The device was rebooted after scope updates so LSPosed could reload module
configuration.

## Build Commands

```powershell
.\gradlew.bat :app:assembleDebug :probe-app:assembleDebug
```

## Install Commands

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r probe-app\build\outputs\apk\debug\probe-app-debug.apk
```

## Permission Setup

```powershell
adb shell pm grant dev.ghostloc.offline android.permission.ACCESS_FINE_LOCATION
adb shell pm grant dev.ghostloc.offline android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant dev.ghostloc.probe android.permission.ACCESS_FINE_LOCATION
adb shell pm grant dev.ghostloc.probe android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant dev.ghostloc.probe android.permission.READ_PHONE_STATE
```

## Automated Test Flow

Configure the module with a target coordinate:

```powershell
adb shell am start -n dev.ghostloc.offline/.MainActivity `
  --ez dev.ghostloc.offline.extra.AUTO_APPLY true `
  --ez dev.ghostloc.offline.extra.ACTIVE true `
  --es dev.ghostloc.offline.extra.LAT 22.319300 `
  --es dev.ghostloc.offline.extra.LON 114.169400 `
  --ez dev.ghostloc.offline.extra.FINISH true
```

Run the probe:

```powershell
adb shell am start -n dev.ghostloc.probe/.ProbeActivity
```

Collect evidence:

```powershell
adb logcat -d | findstr /I "GhostLocApp GhostLocModule GhostLocProbe"
```

## Evidence

### Phase 1 Deep Service Hooks

Test coordinate:

```text
35.689500,139.691700
```

The APKs were rebuilt, installed, LSPosed scope was confirmed, and the device was
rebooted so `system_server` could reload the module.

Static Android 15 ROM inspection found the active OnePlus7 service signatures:

```text
LocationManagerService#getLastLocation(String, LastLocationRequest, String, String): Location
LocationManagerService#getCurrentLocation(String, LocationRequest, ILocationCallback, String, String, String): ICancellationSignal
LocationManagerService#registerLocationListener(String, LocationRequest, ILocationListener, String, String, String): void
LocationProviderManager#getLastLocation(LastLocationRequest, CallerIdentity, int): Location
LocationProviderManager#getCurrentLocation(LocationRequest, CallerIdentity, int, ILocationCallback): ICancellationSignal
AbstractLocationProvider#reportLocation(LocationResult): void
GnssNative#reportLocation(boolean, Location): void
```

Runtime service/binder hook evidence:

```text
GhostLocModule: service trace uid=10500 android.location.ILocationManager$Stub#onTransact(int,android.os.Parcel,android.os.Parcel,int):boolean
GhostLocModule: service current com.android.server.location.provider.LocationProviderManager#getCurrentLocation(android.location.LocationRequest,android.location.util.identity.CallerIdentity,int,android.location.ILocationCallback):android.os.ICancellationSignal
GhostLocModule: service current com.android.server.location.LocationManagerService#getCurrentLocation(java.lang.String,android.location.LocationRequest,android.location.ILocationCallback,java.lang.String,java.lang.String,java.lang.String):android.os.ICancellationSignal
```

Probe output for public API paths and binder-service discovery:

```text
GhostLocProbe: public_last_location=35.689500,139.691700 mock=false provider=gps
GhostLocProbe: public_current_location=35.689500,139.691700 mock=false
GhostLocProbe: public_listener_location=35.689500,139.691700 mock=false provider=gps
GhostLocProbe: binder_service=android.location.ILocationManager$Stub$Proxy
GhostLocProbe: binder_reflection_error=NoSuchMethodException
GhostLocProbe: providers=[gps, network, passive, fused] gps_enabled=true best=gps
GhostLocProbe: location=35.689500,139.691700 speed=3.05 alt=80.0 acc=25.0 mock=false provider=gps
GhostLocProbe: geocoder=1
GhostLocProbe: mock_setting=0
GhostLocProbe: wifi_mac=02:00:00:00:00:00 bssid=02:00:00:00:00:00 ssid="GhostLoc" rssi=-55 freq=2412 link=72 network_id=0 supplicant=COMPLETED scans=0
GhostLocProbe: network_type=13 phone_type=1 operator=00101 operator_name=GhostLoc country=us roaming=false cells=0
```

Result:

- `ILocationManager$Stub#onTransact` was hit in `system_server`.
- `LocationManagerService#getCurrentLocation` was hit in `system_server`.
- `LocationProviderManager#getCurrentLocation` was hit in `system_server`.
- Public last/current/listener probe paths still receive spoofed coordinates.
- No `GhostLocModule` exception, error, failed, or skipped log lines were found
  in the captured logcat output.
- Direct app-side reflective invocation of hidden `ILocationManager#getLastLocation`
  remains blocked by Android 15 hidden API filtering; this does not block the
  verified service-path hook because the public API calls traverse binder and hit
  the service hooks above.

### Phase 3 Structured CellInfo Synthesis

Test coordinate:

```text
35.689500,139.691700
```

The APKs were rebuilt, reinstalled, and the device was rebooted so the updated
module code would reload into the target processes.

Runtime telephony evidence:

```text
GhostLocProbe: public_cells=LTE reg=true status=1 mcc=440 mnc=10 ci=156030181 pci=244 tac=46053 earfcn=1773 rsrp=-99 rsrq=-8 rssnr=148 ta=15 | NR reg=false status=2 mcc=440 mnc=10 nci=12021270607 pci=414 tac=6401 nrarfcn=641682 ssRsrp=-102 ssRsrq=-13 ssSinr=163
GhostLocProbe: public_service_state=state=0 roaming=false operator=44010 alpha=NTT DOCOMO regs=2
GhostLocModule: cell callback android.telephony.TelephonyManager#requestCellInfoUpdate(java.util.concurrent.Executor,android.telephony.TelephonyManager$CellInfoCallback):void
GhostLocProbe: network_type=20 data_type=20 voice_type=13 phone_type=1 operator=44010 operator_name=NTT DOCOMO country=jp roaming=false service_numeric=44010 service_alpha=NTT DOCOMO cells=2
GhostLocProbe: cell_details=LTE reg=true status=1 mcc=440 mnc=10 ci=156030181 pci=244 tac=46053 earfcn=1773 rsrp=-99 rsrq=-8 rssnr=148 ta=15 | NR reg=false status=2 mcc=440 mnc=10 nci=12021270607 pci=414 tac=6401 nrarfcn=641682 ssRsrp=-102 ssRsrq=-13 ssSinr=163
GhostLocProbe: cellinfo_callback=LTE reg=true status=1 mcc=440 mnc=10 ci=156030181 pci=244 tac=46053 earfcn=1773 rsrp=-99 rsrq=-8 rssnr=148 ta=15 | NR reg=false status=2 mcc=440 mnc=10 nci=12021270607 pci=414 tac=6401 nrarfcn=641682 ssRsrp=-102 ssRsrq=-13 ssSinr=163
```

Result:

- `TelephonyManager.getAllCellInfo()` returned two synthetic cells on-device.
- `requestCellInfoUpdate()` callback returned the same synthetic LTE/NR profile.
- operator code/name, country ISO, `ServiceState`, and `CellInfo` identity all
  agreed on `44010 / NTT DOCOMO / jp`.
- `getNetworkType()` and `getDataNetworkType()` moved to `20` (`NR`) while
  `getVoiceNetworkType()` stayed at `13` (`LTE`), matching the synthesized
  voice/data split.
- no `GhostLocModule` exception, error, failed, or skipped lines were present in
  the captured telephony log evidence.

### Phase 4 GNSS Status and NMEA

Test coordinate:

```text
35.689500,139.691700
```

The APKs were rebuilt, reinstalled, and the device was rebooted so the updated
GNSS/NMEA hooks would reload into the probe process.

Runtime GNSS/NMEA evidence:

```text
GhostLocProbe: gnss_started
GhostLocProbe: gnss_first_fix=1100
GhostLocProbe: nmea=$GPGGA,032329.00,3541.3700,N,13941.5020,E,1,12,0.9,80.0,M,0.0,M,,*69
GhostLocProbe: nmea=$GPRMC,032329.00,A,3541.3700,N,13941.5020,E,5.9,0.0,220526,,*39
GhostLocProbe: nmea=$GPGSA,A,3,03,07,09,16,19,22,26,28,31,14,18,1.2,0.9,1.5*12
GhostLocProbe: nmea=$GPGSV,3,1,12,18,36,265,42,18,80,035,30,17,79,071,31,20,61,319,30*76
GhostLocProbe: nmea=$GPGSV,3,2,12,22,33,167,28,19,29,087,36,24,21,150,39,35,27,204,43*71
GhostLocProbe: nmea=$GPGSV,3,3,12,35,16,241,37,20,14,186,37,21,57,005,41,37,72,314,33*72
GhostLocProbe: gps_status_event=1 snapshot=null
GhostLocProbe: gps_status_event=3 snapshot=null
GhostLocProbe: gps_status_event=4 snapshot=null
```

Result:

- `GnssStatus.Callback` received synthetic `onStarted` and `onFirstFix`.
- `OnNmeaMessageListener` received GGA, RMC, GSA, and multi-part GSV sentences.
- NMEA latitude/longitude matched the spoof coordinate `35.689500,139.691700`.
- legacy `GpsStatus.Listener` received synthetic start / first-fix / satellite
  event codes.
- `LocationManager.getGpsStatus(null)` still returned `null` on this ROM, but it
  did not block the GNSS/NMEA callback acceptance path.

### Phase 2 Fused Provider Coverage

Test coordinate:

```text
35.689500,139.691700
```

The ROM exposes installed package `com.android.location.fused` and resolves
service action `com.android.location.service.FusedLocationProvider` to
`com.android.location.fused/.FusedLocationService`.

Owned probe evidence for the public fused provider path:

```text
GhostLocProbe: fused_last_location=35.689500,139.691700 mock=false provider=fused
GhostLocProbe: fused_current_location=35.689500,139.691700 mock=false provider=fused
```

Result:

- `LocationManager.getLastKnownLocation("fused")` returned the spoofed
  coordinate on-device.
- `LocationManager.getCurrentLocation("fused", ...)` returned the spoofed
  coordinate on-device.
- `dumpsys location` showed the active fused target service on this ROM as
  `com.google.android.gms/com.google.android.location.fused.FusedLocationService`.
- no standalone `com.android.location.fused` PID was present during active fused
  requests, so there was no separate current-ROM process to capture beyond the
  public/service-path verification already in place.
- `com.oplus.location` and `com.xiaomi.location.fused` are not installed on the
  current device.

### Phase 5 Product Completeness

Base coordinate and route seed:

```text
base point: 35.689500,139.691700
route points:
- 35.689500,139.691700
- 35.690300,139.693000
- 35.691100,139.694200
favorites:
- Tokyo Base / jp
- Tokyo Alt / jp
```

For owned automation, `MainActivity` was launched with base64-encoded route and
favorites extras, movement enabled, loop enabled, auto-bearing enabled, and
speed variation set to `0.20`. The probe was then run twice: once immediately,
and once again after a full device reboot without reapplying settings.

Runtime evidence before reboot:

```text
GhostLocApp: service bound framework=LSPosed api=100
GhostLocApp: bridge command=start handled=true
GhostLocProbe: public_last_location=35.689540,139.691766 mock=false provider=gps
GhostLocProbe: location=35.689540,139.691766 speed=3.13 alt=82.9 acc=25.5 mock=false provider=gps
GhostLocProbe: nmea=$GPGGA,040135.00,3541.3725,N,13941.5061,E,1,12,0.9,82.9,M,0.0,M,,*6A
GhostLocProbe: probe_status=PASS
GhostLocApp: probe report stored ok=true
```

Persistence evidence after reboot, without reapplying settings:

```text
GhostLocProbe: public_last_location=35.689819,139.692218 mock=false provider=gps
GhostLocProbe: location=35.689819,139.692218 speed=3.07 alt=77.7 acc=23.0 mock=false provider=gps
GhostLocProbe: network_type=20 data_type=20 voice_type=13 phone_type=1 operator=44010 operator_name=NTT DOCOMO country=jp roaming=false service_numeric=44010 service_alpha=NTT DOCOMO cells=2
GhostLocProbe: probe_status=PASS
GhostLocApp: probe report stored ok=true
```

Stored app-side probe report:

```xml
<boolean name="last_probe_ok" value="true" />
<string name="last_probe_report">providers=[gps, network, passive, fused] ...</string>
```

Result:

- the new route path remained active in the hook output:
  - the observed coordinate moved away from the base point
  - `provider=gps` and `provider=fused` stayed consistent
- speed/altitude/accuracy moved off their base values, confirming live
  speed-variation and jitter application in the hook layer
- probe pass/fail now returns into the main app through
  `ProbeReportReceiver`, and the app persists the last probe verdict/report
- route/favorites settings and the Tokyo/Japan profile survived a full device
  reboot on the OnePlus7 path

### Phase 1 Acceptance Closure

Ordinary demo app validation with the module active:

```text
GhostLocDemo: gps_last=35.690985,139.694028 speed=2.52 alt=82.6 acc=25.1 provider=gps mock=false
GhostLocDemo: fused_last=35.690985,139.694028 speed=2.52 alt=82.6 acc=25.1 provider=fused mock=false
GhostLocDemo: gps_current=35.689573,139.691819 speed=3.20 alt=82.8 acc=26.0 provider=gps mock=false
GhostLocDemo: fused_current=35.689573,139.691819 speed=3.20 alt=82.8 acc=26.0 provider=fused mock=false
GhostLocDemo: gps_listener=35.689573,139.691819 speed=3.20 alt=82.8 acc=26.0 provider=gps mock=false
GhostLocModule: service trace uid=10501 android.location.ILocationManager$Stub#onTransact(...)
```

Disable-module restore-real-location validation:

1. Uninstalled `dev.ghostloc.offline`
2. Rebooted the device
3. Reran `demo-app` and `probe-app`

Observed without the module:

```text
GhostLocDemo: gps_last=null
GhostLocDemo: fused_last=null
GhostLocDemo: gps_current=null
GhostLocDemo: fused_current=null
GhostLocProbe: location=null
GhostLocProbe: public_cells=
GhostLocProbe: network_type=0 data_type=0 voice_type=0 phone_type=2 operator= operator_name= country= roaming=false service_numeric=null service_alpha=null cells=0
GhostLocProbe: probe_status=FAIL
```

Restored final device state:

1. Reinstalled `dev.ghostloc.offline`
2. Restored LSPosed module/scope records
3. Rebooted the device
4. Reapplied the Tokyo route profile
5. Reran `demo-app` and `probe-app`

Observed after restore:

```text
GhostLocModule: loaded process=dev.ghostloc.offline
GhostLocDemo: gps_last=35.689573,139.691819 speed=3.19 alt=82.8 acc=26.0 provider=gps mock=false
GhostLocProbe: public_last_location=35.689768,139.692136 mock=false provider=gps
GhostLocProbe: probe_status=PASS
```

Result:

- a second ordinary location app now verifies the spoof path end to end
- uninstalling the module and rebooting restored non-spoofed behavior on the
  owned device path
- the module was reinstalled and verified again, so the final device state is
  back on the active spoof configuration

### Module Load

```text
GhostLocModule: loaded process=dev.ghostloc.offline
GhostLocApp: service bound framework=LSPosed api=100
GhostLocModule: loaded process=dev.ghostloc.probe
```

### Command Bridge

```text
GhostLocModule: bridge command observed=start
GhostLocApp: Bridge command=start handled=true
```

### Probe Output

```text
GhostLocProbe: current_location=22.319300,114.169400 mock=false
GhostLocProbe: providers=[gps, network, passive, fused] gps_enabled=true best=gps
GhostLocProbe: location=22.319300,114.169400 speed=3.05 alt=80.0 acc=25.0 mock=false provider=gps
GhostLocProbe: geocoder=1
GhostLocProbe: mock_setting=0
GhostLocProbe: wifi_mac=02:00:00:00:00:00 bssid=02:00:00:00:00:00 ssid="GhostLoc" rssi=-55 freq=2412 link=72 network_id=0 supplicant=COMPLETED scans=0
GhostLocProbe: network_type=13 phone_type=1 operator=00101 operator_name=GhostLoc country=us roaming=false cells=0
```

## Result

Pass for the currently implemented public API surfaces:

- LSPosed injection works.
- App-side settings are visible to the hook layer.
- Location values are replaced.
- `system_server` `ILocationManager$Stub#onTransact`,
  `LocationManagerService#getCurrentLocation`, and
  `LocationProviderManager#getCurrentLocation` are hit on the Android 15 test
  device.
- `Location.isMock` / `isFromMockProvider` return false.
- `Settings.Secure.mock_location` returns `0`.
- Wi-Fi identity and scan results are cleaned.
- Basic telephony fields are cleaned.
- Structured LTE/NR `CellInfo` synthesis is active.
- `requestCellInfoUpdate()` synthetic callback path is active.
- `GnssStatus.Callback` and NMEA synthetic callback paths are active.
- Public `fused` provider coordinate reads are active.
- Phase 5 route controls, diagnostics, and in-app probe reporting are active.
- a second ordinary location demo app receives spoofed coordinates.
- uninstall + reboot restores non-spoofed behavior on the owned device path.
- Provider status and Geocoder are spoofed.
- Command bridge is observed without LSPosed error stacks after the read-only
  remote preferences fix.

## Open Test Gaps

- direct app-side hidden `ILocationManager#getLastLocation` reflection is blocked
  by Android 15 hidden API filtering
- legacy `LocationManager.getGpsStatus(null)` snapshot remains `null` on the
  current ROM
- multi-device / multi-ROM test matrix
