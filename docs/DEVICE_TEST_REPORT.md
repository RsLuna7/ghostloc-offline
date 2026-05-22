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
- Provider status and Geocoder are spoofed.
- Command bridge is observed without LSPosed error stacks after the read-only
  remote preferences fix.

## Open Test Gaps

- direct app-side hidden `ILocationManager#getLastLocation` reflection is blocked
  by Android 15 hidden API filtering
- separate ordinary third-party demo app validation
- module disable/restore-real-location validation
- non-empty structured `CellInfo` objects
- GNSS/NMEA callbacks
- vendor fused-location process behavior
- multi-device / multi-ROM test matrix
