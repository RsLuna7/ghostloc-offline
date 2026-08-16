package dev.ghostloc.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.GnssStatus;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.Executors;

public final class ProbeActivity extends Activity {
    private static final String TAG = "GhostLocProbe";
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        output = new TextView(this);
        output.setTextSize(14);
        output.setText("Running GhostLoc probe. Check logcat tag GhostLocProbe.");
        setContentView(output);
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_PHONE_STATE
            }, 7);
            return;
        }
        runProbe();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        runProbe();
    }

    private void runProbe() {
        StringBuilder report = new StringBuilder();
        probeLocation(report);
        probeGnss(report);
        probeGnssMeasurements(report);
        probeTestProvider(report);
        probeGeocoder(report);
        probeSettings(report);
        probeWifi(report);
        probeTelephony(report);
        String value = report.toString();
        Log.i(TAG, value);
        boolean ok = isGreenReport(value);
        Log.i(TAG, "probe_status=" + (ok ? "PASS" : "FAIL"));
        sendReportBroadcast(value, ok);
        output.setText(value);
    }

    private boolean isGreenReport(String report) {
        return report != null
                && report.contains("location=")
                && report.contains("network_type=")
                && !report.contains("location=null")
                && !report.contains("_error=");
    }

    private void sendReportBroadcast(String report, boolean ok) {
        try {
            Intent intent = new Intent("dev.ghostloc.offline.action.PROBE_REPORT");
            intent.setPackage("dev.ghostloc.offline");
            intent.putExtra("probe_report", report);
            intent.putExtra("probe_ok", ok);
            intent.putExtra("probe_at", System.currentTimeMillis());
            sendBroadcast(intent);
        } catch (Throwable throwable) {
            Log.i(TAG, "probe_broadcast_error=" + throwable.getClass().getSimpleName());
        }
    }

    private void probeLocation(StringBuilder report) {
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            report.append("providers=").append(manager.getAllProviders())
                    .append(" gps_enabled=").append(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    .append(" best=").append(manager.getBestProvider(new Criteria(), true))
                    .append("\n");
            if (location == null) {
                report.append("location=null\n");
                return;
            }
            report.append(String.format(Locale.US,
                    "location=%.6f,%.6f speed=%.2f alt=%.1f acc=%.1f mock=%s provider=%s\n",
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getSpeed(),
                    location.getAltitude(),
                    location.getAccuracy(),
                    isMock(location),
                    location.getProvider()));
            Log.i(TAG, String.format(Locale.US,
                    "public_last_location=%.6f,%.6f mock=%s provider=%s",
                    location.getLatitude(),
                    location.getLongitude(),
                    isMock(location),
                    location.getProvider()));
            probeJitter(manager);
            Location fusedLocation = null;
            try {
                fusedLocation = manager.getLastKnownLocation(LocationManager.FUSED_PROVIDER);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, String.format(Locale.US,
                    "fused_last_location=%.6f,%.6f mock=%s provider=%s",
                    fusedLocation == null ? 0.0 : fusedLocation.getLatitude(),
                    fusedLocation == null ? 0.0 : fusedLocation.getLongitude(),
                    fusedLocation != null && isMock(fusedLocation),
                    fusedLocation == null ? "null" : fusedLocation.getProvider()));
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, new CancellationSignal(),
                        Executors.newSingleThreadExecutor(),
                        current -> Log.i(TAG, String.format(Locale.US,
                                "public_current_location=%.6f,%.6f mock=%s",
                                current == null ? 0.0 : current.getLatitude(),
                                current == null ? 0.0 : current.getLongitude(),
                                current != null && isMock(current))));
                manager.getCurrentLocation(LocationManager.FUSED_PROVIDER, new CancellationSignal(),
                        Executors.newSingleThreadExecutor(),
                        current -> Log.i(TAG, String.format(Locale.US,
                                "fused_current_location=%.6f,%.6f mock=%s provider=%s",
                                current == null ? 0.0 : current.getLatitude(),
                                current == null ? 0.0 : current.getLongitude(),
                                current != null && isMock(current),
                                current == null ? "null" : current.getProvider())));
            }
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location value) {
                    Log.i(TAG, String.format(Locale.US,
                            "public_listener_location=%.6f,%.6f mock=%s provider=%s",
                            value.getLatitude(),
                            value.getLongitude(),
                            isMock(value),
                            value.getProvider()));
                    try {
                        manager.removeUpdates(this);
                    } catch (Throwable throwable) {
                        Log.i(TAG, "public_listener_remove_error=" + throwable.getClass().getSimpleName());
                    }
                }
            };
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener);
            probeBinderReflection(manager);
        } catch (Throwable throwable) {
            report.append("location_error=").append(throwable.getClass().getSimpleName()).append("\n");
        }
    }

    /**
     * Repeated last-known reads while spoofing each return a freshly generated
     * location, so the spread across samples shows the per-output micro-drift
     * and the synthesized GNSS extras reaching apps. With spoofing inactive
     * the cached real fix repeats and the spread stays 0.
     */
    private void probeJitter(LocationManager manager) {
        try {
            Location first = null;
            float maxSpread = 0f;
            int withSatExtras = 0;
            final int samples = 6;
            for (int i = 0; i < samples; i++) {
                Location sample = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (sample == null) {
                    break;
                }
                if (first == null) {
                    first = new Location(sample);
                } else {
                    float[] dist = new float[1];
                    Location.distanceBetween(first.getLatitude(), first.getLongitude(),
                            sample.getLatitude(), sample.getLongitude(), dist);
                    maxSpread = Math.max(maxSpread, dist[0]);
                }
                if (sample.getExtras() != null && sample.getExtras().containsKey("satellites")) {
                    withSatExtras++;
                }
            }
            Log.i(TAG, String.format(Locale.US,
                    "jitter_samples=%d spread=%.2fm satellites_extras=%d/%d",
                    first == null ? 0 : samples, maxSpread, withSatExtras, samples));
        } catch (Throwable throwable) {
            Log.i(TAG, "jitter_samples_error=" + throwable.getClass().getSimpleName());
        }
    }

    private void probeBinderReflection(LocationManager manager) {
        try {
            Object service = serviceFromServiceManager();
            if (service == null) {
                service = serviceFromLocationManager(manager);
            }
            Log.i(TAG, "binder_service=" + (service == null ? "null" : service.getClass().getName()));
            if (service == null) {
                return;
            }
            Location location = invokeBinderGetLastLocation(service);
            Log.i(TAG, String.format(Locale.US,
                    "binder_last_location=%.6f,%.6f mock=%s provider=%s",
                    location == null ? 0.0 : location.getLatitude(),
                    location == null ? 0.0 : location.getLongitude(),
                    location != null && isMock(location),
                    location == null ? "null" : location.getProvider()));
        } catch (Throwable throwable) {
            Log.i(TAG, "binder_reflection_error=" + throwable.getClass().getSimpleName());
        }
    }

    private Location invokeBinderGetLastLocation(Object service) throws Exception {
        Location location = invokeBinderGetLastLocation(service, service.getClass().getMethods());
        if (location != null) {
            return location;
        }
        Class<?> managerInterface = Class.forName("android.location.ILocationManager");
        location = invokeBinderGetLastLocation(service, managerInterface.getMethods());
        if (location != null) {
            return location;
        }
        throw new NoSuchMethodException("getLastLocation");
    }

    private Location invokeBinderGetLastLocation(Object service, Method[] methods) throws Exception {
        for (Method method : methods) {
            if (!"getLastLocation".equals(method.getName())) {
                continue;
            }
            Log.i(TAG, "binder_getLast_method=" + method);
            Object[] args = argsForBinderGetLast(method.getParameterTypes());
            method.setAccessible(true);
            Object result = method.invoke(service, args);
            if (result instanceof Location) {
                return (Location) result;
            }
            return null;
        }
        return null;
    }

    private Object[] argsForBinderGetLast(Class<?>[] types) throws Exception {
        Object[] args = new Object[types.length];
        boolean providerStringUsed = false;
        boolean packageStringUsed = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            String name = type.getName();
            if (type == String.class) {
                if (!providerStringUsed) {
                    args[i] = LocationManager.GPS_PROVIDER;
                    providerStringUsed = true;
                } else if (!packageStringUsed) {
                    args[i] = getPackageName();
                    packageStringUsed = true;
                } else {
                    args[i] = null;
                }
            } else if ("android.location.LastLocationRequest".equals(name)) {
                args[i] = newLastLocationRequest();
            } else if (type == int.class || type == Integer.class) {
                args[i] = 0;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = false;
            } else if (type == long.class || type == Long.class) {
                args[i] = 0L;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private Object newLastLocationRequest() throws Exception {
        Class<?> builderClass = Class.forName("android.location.LastLocationRequest$Builder");
        Object builder = builderClass.getConstructor().newInstance();
        return builderClass.getMethod("build").invoke(builder);
    }

    private Object serviceFromServiceManager() {
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object binder = getService.invoke(null, Context.LOCATION_SERVICE);
            if (!(binder instanceof IBinder)) {
                return null;
            }
            Class<?> stubClass = Class.forName("android.location.ILocationManager$Stub");
            Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            return asInterface.invoke(null, binder);
        } catch (Throwable throwable) {
            Log.i(TAG, "binder_servicemanager_error=" + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private Object serviceFromLocationManager(LocationManager manager) {
        Class<?> cls = manager.getClass();
        while (cls != null) {
            try {
                for (Field field : cls.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(manager);
                        if (value != null && value.getClass().getName().contains("ILocationManager")) {
                            Log.i(TAG, "binder_service_field=" + cls.getName() + "." + field.getName());
                            return value;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable throwable) {
                Log.i(TAG, "binder_field_scan_error=" + throwable.getClass().getSimpleName());
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private void probeGnss(StringBuilder report) {
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) {
                report.append("gnss_manager=null\n");
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                manager.registerGnssStatusCallback(
                        Executors.newSingleThreadExecutor(),
                        new GnssStatus.Callback() {
                            @Override
                            public void onStarted() {
                                Log.i(TAG, "gnss_started");
                            }

                            @Override
                            public void onFirstFix(int ttffMillis) {
                                Log.i(TAG, "gnss_first_fix=" + ttffMillis);
                            }

                            @Override
                            public void onSatelliteStatusChanged(GnssStatus status) {
                                Log.i(TAG, "gnss_status=" + describeGnssStatus(status));
                            }
                        });
            }
            manager.addGpsStatusListener(event ->
                    Log.i(TAG, "gps_status_event=" + event + " snapshot=" + describeGpsStatus(manager.getGpsStatus(null))));
            manager.addNmeaListener(
                    Executors.newSingleThreadExecutor(),
                    (message, timestamp) -> Log.i(TAG, "nmea=" + message));
            GpsStatus gpsStatus = manager.getGpsStatus(null);
            report.append("gps_status=").append(describeGpsStatus(gpsStatus)).append("\n");
        } catch (Throwable throwable) {
            report.append("gnss_error=").append(throwable.getClass().getSimpleName()).append("\n");
        }
    }

    private String describeGnssStatus(GnssStatus status) {
        if (status == null) {
            return "null";
        }
        StringJoiner joiner = new StringJoiner(" | ");
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            joiner.add(String.format(Locale.US, "#%d c=%d cn0=%.1f el=%.1f az=%.1f used=%s",
                    status.getSvid(i),
                    status.getConstellationType(i),
                    status.getCn0DbHz(i),
                    status.getElevationDegrees(i),
                    status.getAzimuthDegrees(i),
                    status.usedInFix(i)));
        }
        return "count=" + status.getSatelliteCount() + " " + joiner;
    }

    private String describeGpsStatus(GpsStatus status) {
        if (status == null) {
            return "null";
        }
        List<String> satellites = new ArrayList<>();
        for (GpsSatellite satellite : status.getSatellites()) {
            satellites.add(String.format(Locale.US, "#%d snr=%.1f el=%.1f az=%.1f used=%s",
                    satellite.getPrn(),
                    satellite.getSnr(),
                    satellite.getElevation(),
                    satellite.getAzimuth(),
                    satellite.usedInFix()));
        }
        return "ttff=" + status.getTimeToFirstFix() + " count=" + satellites.size()
                + " " + String.join(" | ", satellites);
    }

    private void probeGnssMeasurements(StringBuilder report) {
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (manager == null || android.os.Build.VERSION.SDK_INT < 24) {
                report.append("gnss_meas_registered=skipped\n");
                return;
            }
            android.location.GnssMeasurementsEvent.Callback callback =
                    new android.location.GnssMeasurementsEvent.Callback() {
                        @Override
                        public void onGnssMeasurementsReceived(android.location.GnssMeasurementsEvent event) {
                            Log.i(TAG, "gnss_meas_event=received count="
                                    + (event == null ? -1 : event.getMeasurements().size()));
                        }
                    };
            boolean registered;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                registered = manager.registerGnssMeasurementsCallback(
                        Executors.newSingleThreadExecutor(), callback);
            } else {
                registered = manager.registerGnssMeasurementsCallback(callback);
            }
            report.append("gnss_meas_registered=").append(registered).append("\n");
            try {
                manager.unregisterGnssMeasurementsCallback(callback);
            } catch (Throwable ignored) {
            }
        } catch (Throwable throwable) {
            report.append("gnss_meas_registered=").append(throwable.getClass().getSimpleName()).append("\n");
        }
    }

    private void probeTestProvider(StringBuilder report) {
        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            report.append("test_provider_present=skipped\n");
            return;
        }
        String name = "ghostloc_probe_test";
        try {
            manager.addTestProvider(name, false, false, false, false, true, true, true, 1, 1);
        } catch (Throwable throwable) {
            Log.i(TAG, "test_provider_add=" + throwable.getClass().getSimpleName());
        }
        boolean present = false;
        try {
            present = manager.getAllProviders().contains(name) || manager.getProvider(name) != null;
        } catch (Throwable ignored) {
        }
        report.append("test_provider_present=").append(present).append("\n");
        try {
            manager.removeTestProvider(name);
        } catch (Throwable ignored) {
        }
    }

    private void probeGeocoder(StringBuilder report) {
        try {
            Geocoder geocoder = new Geocoder(this, Locale.US);
            List<?> addresses = geocoder.getFromLocation(1.0, 2.0, 1);
            report.append("geocoder=").append(addresses == null ? -1 : addresses.size()).append("\n");
        } catch (Throwable throwable) {
            report.append("geocoder_error=").append(throwable.getClass().getSimpleName()).append("\n");
        }
    }

    private void probeSettings(StringBuilder report) {
        try {
            String mock = Settings.Secure.getString(getContentResolver(), "mock_location");
            report.append("mock_setting=").append(mock).append("\n");
        } catch (Throwable throwable) {
            report.append("settings_error=").append(throwable.getClass().getSimpleName()).append("\n");
        }
    }

    private void probeWifi(StringBuilder report) {
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = manager.getConnectionInfo();
            List<?> scans = manager.getScanResults();
            report.append("wifi_mac=").append(info == null ? "null" : info.getMacAddress())
                    .append(" bssid=").append(info == null ? "null" : info.getBSSID())
                    .append(" ssid=").append(info == null ? "null" : info.getSSID())
                    .append(" rssi=").append(info == null ? 0 : info.getRssi())
                    .append(" freq=").append(info == null ? 0 : info.getFrequency())
                    .append(" link=").append(info == null ? 0 : info.getLinkSpeed())
                    .append(" network_id=").append(info == null ? -99 : info.getNetworkId())
                    .append(" supplicant=").append(info == null ? "null" : info.getSupplicantState())
                    .append(" scans=").append(scans == null ? -1 : scans.size())
                    .append("\n");
        } catch (Throwable throwable) {
            report.append("wifi_error=").append(throwable.getClass().getSimpleName()).append("\n");
        }
    }

    private void probeTelephony(StringBuilder report) {
        try {
            TelephonyManager manager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            List<CellInfo> cells = manager.getAllCellInfo();
            ServiceState serviceState = manager.getServiceState();
            report.append("network_type=").append(manager.getNetworkType())
                    .append(" data_type=").append(manager.getDataNetworkType())
                    .append(" voice_type=").append(manager.getVoiceNetworkType())
                    .append(" phone_type=").append(manager.getPhoneType())
                    .append(" operator=").append(manager.getNetworkOperator())
                    .append(" operator_name=").append(manager.getNetworkOperatorName())
                    .append(" country=").append(manager.getNetworkCountryIso())
                    .append(" roaming=").append(manager.isNetworkRoaming())
                    .append(" service_numeric=").append(serviceState == null ? "null" : serviceState.getOperatorNumeric())
                    .append(" service_alpha=").append(serviceState == null ? "null" : serviceState.getOperatorAlphaLong())
                    .append(" cells=").append(cells == null ? -1 : cells.size())
                    .append("\n")
                    .append("cell_details=").append(describeCells(cells))
                    .append("\n");
            Log.i(TAG, "public_cells=" + describeCells(cells));
            Log.i(TAG, "public_service_state=" + describeServiceState(serviceState));
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                manager.requestCellInfoUpdate(
                        Executors.newSingleThreadExecutor(),
                        new TelephonyManager.CellInfoCallback() {
                            @Override
                            public void onCellInfo(List<CellInfo> value) {
                                Log.i(TAG, "cellinfo_callback=" + describeCells(value));
                            }

                            @Override
                            public void onError(int errorCode, Throwable detail) {
                                Log.i(TAG, "cellinfo_callback_error=" + errorCode + ","
                                        + (detail == null ? "null" : detail.getClass().getSimpleName()));
                            }
                        });
            }
        } catch (Throwable throwable) {
            report.append("telephony_error=").append(throwable.getClass().getSimpleName()).append("\n");
        }
    }

    private String describeServiceState(ServiceState state) {
        if (state == null) {
            return "null";
        }
        return "state=" + state.getState()
                + " roaming=" + state.getRoaming()
                + " operator=" + state.getOperatorNumeric()
                + " alpha=" + state.getOperatorAlphaLong()
                + " regs=" + state.getNetworkRegistrationInfoList().size();
    }

    private String describeCells(List<CellInfo> cells) {
        if (cells == null) {
            return "null";
        }
        StringJoiner joiner = new StringJoiner(" | ");
        for (CellInfo cell : cells) {
            joiner.add(describeCell(cell));
        }
        return joiner.toString();
    }

    private String describeCell(CellInfo cell) {
        if (cell == null) {
            return "null";
        }
        if (cell instanceof CellInfoLte lte) {
            CellIdentityLte identity = lte.getCellIdentity();
            CellSignalStrengthLte signal = lte.getCellSignalStrength();
            return String.format(Locale.US,
                    "LTE reg=%s status=%d mcc=%s mnc=%s ci=%d pci=%d tac=%d earfcn=%d rsrp=%d rsrq=%d rssnr=%d ta=%d",
                    lte.isRegistered(),
                    lte.getCellConnectionStatus(),
                    identity == null ? "null" : identity.getMccString(),
                    identity == null ? "null" : identity.getMncString(),
                    identity == null ? -1 : identity.getCi(),
                    identity == null ? -1 : identity.getPci(),
                    identity == null ? -1 : identity.getTac(),
                    identity == null ? -1 : identity.getEarfcn(),
                    signal == null ? 0 : signal.getRsrp(),
                    signal == null ? 0 : signal.getRsrq(),
                    signal == null ? 0 : signal.getRssnr(),
                    signal == null ? 0 : signal.getTimingAdvance());
        }
        if (cell instanceof CellInfoNr nr) {
            CellIdentityNr identity = (CellIdentityNr) nr.getCellIdentity();
            CellSignalStrengthNr signal = (CellSignalStrengthNr) nr.getCellSignalStrength();
            return String.format(Locale.US,
                    "NR reg=%s status=%d mcc=%s mnc=%s nci=%d pci=%d tac=%d nrarfcn=%d ssRsrp=%d ssRsrq=%d ssSinr=%d",
                    nr.isRegistered(),
                    nr.getCellConnectionStatus(),
                    identity == null ? "null" : identity.getMccString(),
                    identity == null ? "null" : identity.getMncString(),
                    identity == null ? 0L : identity.getNci(),
                    identity == null ? -1 : identity.getPci(),
                    identity == null ? -1 : identity.getTac(),
                    identity == null ? -1 : identity.getNrarfcn(),
                    signal == null ? 0 : signal.getSsRsrp(),
                    signal == null ? 0 : signal.getSsRsrq(),
                    signal == null ? 0 : signal.getSsSinr());
        }
        return cell.getClass().getSimpleName() + " reg=" + cell.isRegistered()
                + " status=" + cell.getCellConnectionStatus();
    }

    private boolean isMock(Location location) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }
}
