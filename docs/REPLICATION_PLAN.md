# Validation and Compatibility Plan

This plan now tracks the remaining compatibility and validation follow-up work
after the owned-device implementation path reached completion.

## Current Completion

Approximate completion: 98-100% of the owned-device feature and validation surface.

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
- structured LTE/NR `CellInfo` synthesis
- GNSS status and NMEA callback synthesis
- public `fused` provider spoof verification
- product control surface for routes, favorites, diagnostics, and probe status
- second ordinary location demo app verification
- disable-module restore-real-location verification
- owned probe app and real-device verification

Not complete:

- broad compatibility testing

## Phase 1 - Deep Location Service Hooks

Goal: move from app-level public API hooks into the `system_server` service path.

Status: implemented and verified on OnePlus7 / GM1901 / Android SDK 35 for the
owned probe path and owned ordinary demo path.

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
- a second ordinary location demo app receives spoofed coordinates - done
- no LSPosed exception stacks - done for captured GhostLoc logs
- disabling the module restores real location behavior - done by uninstall +
  reboot validation on the owned device

Phase 1 residual gaps:

- Android 15 hidden API filtering blocks direct app-side reflective invocation of
  hidden `ILocationManager#getLastLocation`; the probe logs
  `binder_service=android.location.ILocationManager$Stub$Proxy` followed by
  `binder_reflection_error=NoSuchMethodException`
- service-path verification currently relies on system_server logs showing
  `ILocationManager$Stub#onTransact`, `LocationManagerService#getCurrentLocation`,
  and `LocationProviderManager#getCurrentLocation` hook hits

## Phase 2 - Fused and Vendor Location Adaptation

Goal: cover fused and vendor-specific location processes when present.

Status: implemented and verified on OnePlus7 / GM1901 / Android SDK 35 for the
owned device path. The current ROM does not surface a standalone
`com.android.location.fused` process during active fused requests; the active
fused target service is `com.google.android.gms/com.google.android.location.fused.FusedLocationService`.

Target scopes:

- `com.android.location.fused`
- `com.oplus.location`
- `com.xiaomi.location.fused`

Work items:

- add process-specific discovery logs - partially done
- scan target class loaders for classes/methods returning - narrowed to ROM
  allowlist classes instead of broad scanning:
  - `Location`
  - `List<Location>`
  - callback interfaces with `onLocationChanged`
- hook common fused-provider return and callback patterns - partially done for
  `FusedLocationProvider` allowlist candidates
- keep an allowlist of known-safe class/method names per ROM - done
- add a runtime flag for disabling vendor hooks if a ROM misbehaves - done

Acceptance criteria:

- `fused` provider reports spoofed coordinates where available - done on the
  owned probe path
- vendor process hooks do not crash if classes are missing - done
- logs show which hooks were installed and which were skipped - done for the
  current allowlist and current ROM findings

Phase 2 residual gaps:

- `com.oplus.location` and `com.xiaomi.location.fused` are not installed on the
  current device, so those paths remain defensive placeholders pending another
  ROM

## Phase 3 - Structured CellInfo Synthesis

Goal: replace the current empty-cell-list behavior with internally consistent
synthetic cell data.

Status: implemented and verified on OnePlus7 / GM1901 / Android SDK 35 for the
owned probe path.

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

- add `CellProfile` settings storage - done
- implement LTE profile generation from selected coordinate - done
- implement optional NR profile generation - done
- synthesize - done:
  - `CellInfoLte`
  - `CellIdentityLte`
  - `CellSignalStrengthLte`
  - `CellInfoNr` where supported
- handle Android 10-15 constructor differences by reflection - done on Android 15
- fall back to Parcel-based object creation if reflection is not enough - not
  needed on the current device
- hook `requestCellInfoUpdate` callback path - done
- fill `ServiceState` registration/operator fields consistently - done

Acceptance criteria:

- `TelephonyManager.getAllCellInfo()` returns non-empty synthetic cell data -
  done
- `requestCellInfoUpdate()` receives non-empty synthetic cell data - done
- operator, country, service state, and cell identity agree - done
- no crash when SIM is absent or permissions are partial - no crash observed on
  owned probe path; broader app matrix still open

Phase 3 residual gaps:

- broader third-party app validation is still open
- Parcel-based fallback remains available as a contingency item if another ROM
  blocks the constructor-plus-field population path

## Phase 4 - GNSS, NMEA, and Satellite State

Goal: make GPS environment data consistent with the spoofed location.

Status: implemented and verified on OnePlus7 / GM1901 / Android SDK 35 for the
owned probe callback path.

Work items:

- hook `GnssStatus.Callback` - done
- hook legacy `GpsStatus.Listener` where available - done for event delivery
- hook `OnNmeaMessageListener` - done
- generate stable satellite constellation data - done:
  - satellite count
  - SNR/CN0
  - azimuth
  - elevation
  - used-in-fix flags
- generate NMEA sentences - done:
  - `$GPGGA`
  - `$GPRMC`
  - `$GPGSA`
  - `$GPGSV`
- derive NMEA latitude/longitude/time/speed/course from current spoof state - done
- add UI controls - done:
  - satellite count
  - NMEA enable
  - AGPS enable

Acceptance criteria:

- probe receives GNSS status callbacks - done
- probe receives NMEA messages - done
- NMEA coordinate matches selected coordinate - done
- satellite state remains stable across repeated reads - done for current owned
  probe path

Phase 4 residual gaps:

- `LocationManager.getGpsStatus(null)` still returns `null` on the current ROM,
  even though the GNSS callback and NMEA paths are spoofed

## Phase 5 - Product Completeness

Goal: close the user-facing gap between a technical MVP and a complete module.

Status: implemented and verified on OnePlus7 / GM1901 / Android SDK 35 for the
owned route/probe path, including post-reboot persistence.

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

- no adb required for normal use - done
- user can diagnose missing scope/module issues in app - done
- probe all-green result is visible from the app - done
- settings survive reboot and app restart - done on the owned device path

Phase 5 implementation notes:

- `MainActivity` now exposes:
  - route JSON editing and current-point append
  - loop / pause / resume / auto-bearing controls
  - named/grouped favorites JSON import/export
  - GNSS, NR, vendor-hook, and jitter controls
  - LSPosed/module/probe diagnostics
- `GhostLocModule` now applies:
  - multi-point route interpolation
  - loop handling
  - speed variation
  - altitude/accuracy jitter
  - recent hook hit/error diagnostics
- `probe-app` now broadcasts its final report and pass/fail verdict back to the
  main app, which persists and displays the last probe result

## Remaining Validation Order

1. Compatibility follow-up: broaden the device / ROM / third-party app matrix
2. Optional vendor follow-up: exercise Oplus/Xiaomi placeholder allowlists on a
   ROM where those packages actually exist

Reasoning:

- Core implementation and owned-device validation phases 1 through 5 are now in
  place on the Android 15 OnePlus7 path.
- The remaining work is cross-ROM coverage, not missing owned-device product
  behavior.
