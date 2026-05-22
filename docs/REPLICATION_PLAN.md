# Remaining Replication Plan

This plan tracks the remaining work needed to move from the current verified
offline MVP toward a more complete original-equivalent feature set.

## Current Completion

Approximate completion: 70-75% of the visible/core feature surface.

Completed:

- offline operation without authorization
- map selection and local controls
- LSPosed module loading
- command bridge shape through `sendExtraCommand("ghostloc", ...)`
- location spoofing on public `LocationManager` APIs
- provider-state spoofing
- mock-location hiding
- Geocoder spoofing
- Wi-Fi cleanup
- basic telephony cleanup
- Android 15 OnePlus7 `system_server` location service hooks
- owned probe app and real-device verification

Not complete:

- broader third-party app service-path compatibility checks
- vendor fused-location hooks
- non-empty structured cell identity synthesis
- GNSS/NMEA/satellite state
- polished route UI and product controls
- broad compatibility testing

## Phase 1 - Deep Location Service Hooks

Goal: move from app-level public API hooks into the `system_server` service path.

Status: implemented and verified on OnePlus7 / GM1901 / Android SDK 35 for the
owned probe path. Residual Phase 1 acceptance gaps are listed below.

Work items:

- inspect actual Android 15 method signatures on the OnePlus7 ROM - done
- hook `LocationManagerService` - done
- hook `android.location.ILocationManager$Stub` - done through `onTransact`
- cover binder/service paths - partially done:
  - `getLastLocation`
  - `requestLocationUpdates` / public API listener path dispatch
  - `getCurrentLocation` verified through `LocationManagerService` and
    `LocationProviderManager`
  - `registerLocationListener`
  - `removeUpdates`
  - `reportLocation`
- add reflection-based method discovery logs for target classes - done
- add defensive method-signature matching so unsupported ROMs fail closed - done
- extend `probe-app` to distinguish public API path from service/binder path -
  done for public path labels and binder-service object discovery

Acceptance criteria:

- owned probe still passes - done
- a second ordinary location demo app receives spoofed coordinates - still open
- no LSPosed exception stacks - done for captured GhostLoc logs
- disabling the module restores real location behavior - still open

Phase 1 residual gaps:

- Android 15 hidden API filtering blocks direct app-side reflective invocation of
  hidden `ILocationManager#getLastLocation`; the probe logs
  `binder_service=android.location.ILocationManager$Stub$Proxy` followed by
  `binder_reflection_error=NoSuchMethodException`
- service-path verification currently relies on system_server logs showing
  `ILocationManager$Stub#onTransact`, `LocationManagerService#getCurrentLocation`,
  and `LocationProviderManager#getCurrentLocation` hook hits
- add or use a second ordinary demo app before considering Phase 1 fully closed
- run a disable-module restore-real-location check before considering Phase 1
  fully closed

## Phase 2 - Fused and Vendor Location Adaptation

Goal: cover fused and vendor-specific location processes when present.

Target scopes:

- `com.android.location.fused`
- `com.oplus.location`
- `com.xiaomi.location.fused`

Work items:

- add process-specific discovery logs
- scan target class loaders for classes/methods returning:
  - `Location`
  - `List<Location>`
  - callback interfaces with `onLocationChanged`
- hook common fused-provider return and callback patterns
- keep an allowlist of known-safe class/method names per ROM
- add a runtime flag for disabling vendor hooks if a ROM misbehaves

Acceptance criteria:

- `fused` provider reports spoofed coordinates where available
- vendor process hooks do not crash if classes are missing
- logs show which hooks were installed and which were skipped

## Phase 3 - Structured CellInfo Synthesis

Goal: replace the current empty-cell-list behavior with internally consistent
synthetic cell data.

Data model:

```text
CellProfile
- mcc
- mnc
- tac/lac
- ci/cid
- pci
- earfcn/nrarfcn
- rsrp
- rsrq
- rssnr
- timing advance
- registered
```

Work items:

- add `CellProfile` settings storage
- implement LTE profile generation from selected coordinate
- implement optional NR profile generation
- synthesize:
  - `CellInfoLte`
  - `CellIdentityLte`
  - `CellSignalStrengthLte`
  - `CellInfoNr` where supported
- handle Android 10-15 constructor differences by reflection
- fall back to Parcel-based object creation if reflection is not enough
- hook `requestCellInfoUpdate` callback path
- fill `ServiceState` registration/operator fields consistently

Acceptance criteria:

- `TelephonyManager.getAllCellInfo()` returns non-empty synthetic cell data
- `requestCellInfoUpdate()` receives non-empty synthetic cell data
- operator, country, service state, and cell identity agree
- no crash when SIM is absent or permissions are partial

## Phase 4 - GNSS, NMEA, and Satellite State

Goal: make GPS environment data consistent with the spoofed location.

Work items:

- hook `GnssStatus.Callback`
- hook legacy `GpsStatus.Listener` where available
- hook `OnNmeaMessageListener`
- generate stable satellite constellation data:
  - satellite count
  - SNR/CN0
  - azimuth
  - elevation
  - used-in-fix flags
- generate NMEA sentences:
  - `$GPGGA`
  - `$GPRMC`
  - `$GPGSA`
  - `$GPGSV`
- derive NMEA latitude/longitude/time/speed/course from current spoof state
- add UI controls:
  - satellite count
  - NMEA enable
  - AGPS enable

Acceptance criteria:

- probe receives GNSS status callbacks
- probe receives NMEA messages
- NMEA coordinate matches selected coordinate
- satellite state remains stable across repeated reads

## Phase 5 - Product Completeness

Goal: close the user-facing gap between a technical MVP and a complete module.

Work items:

- route movement:
  - multi-point routes
  - loop mode
  - pause/resume
  - speed variation
  - bearing auto-calculation
- favorites:
  - names
  - groups
  - import/export JSON
- configuration:
  - Wi-Fi policy
  - cell policy
  - fused-provider policy
  - force LTE / downgrade mode
  - accuracy jitter
  - altitude jitter
- diagnostics:
  - LSPosed service state
  - module enabled state
  - scope check
  - recent hook hits
  - recent hook errors
  - one-click probe run
- UX cleanup:
  - replace ad hoc UI rows with a maintainable layout
  - add clear status text
  - add reset-to-defaults

Acceptance criteria:

- no adb required for normal use
- user can diagnose missing scope/module issues in app
- probe all-green result is visible from the app
- settings survive reboot and app restart

## Recommended Execution Order

1. Phase 1: deep location service hooks
2. Phase 3: structured cell synthesis
3. Phase 4: GNSS/NMEA
4. Phase 2: vendor fused-location coverage
5. Phase 5: product completeness

Reasoning:

- Phase 1 and Phase 3 are the main technical gaps.
- Phase 4 improves realism and consistency.
- Phase 2 needs per-ROM discovery and should be added after the core path is
  stable.
- Phase 5 is best done after the behavior surface stops moving.
