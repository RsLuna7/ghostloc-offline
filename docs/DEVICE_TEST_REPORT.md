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
- `Location.isMock` / `isFromMockProvider` return false.
- `Settings.Secure.mock_location` returns `0`.
- Wi-Fi identity and scan results are cleaned.
- Basic telephony fields are cleaned.
- Provider status and Geocoder are spoofed.
- Command bridge is observed without LSPosed error stacks after the read-only
  remote preferences fix.

## Open Test Gaps

- private `system_server` binder-path behavior
- non-empty structured `CellInfo` objects
- GNSS/NMEA callbacks
- vendor fused-location process behavior
- multi-device / multi-ROM test matrix

