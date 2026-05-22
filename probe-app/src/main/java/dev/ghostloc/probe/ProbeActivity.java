package dev.ghostloc.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.Criteria;
import android.location.Geocoder;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
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
        probeGeocoder(report);
        probeSettings(report);
        probeWifi(report);
        probeTelephony(report);
        String value = report.toString();
        Log.i(TAG, value);
        output.setText(value);
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
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, new CancellationSignal(),
                        Executors.newSingleThreadExecutor(),
                        current -> Log.i(TAG, String.format(Locale.US,
                                "public_current_location=%.6f,%.6f mock=%s",
                                current == null ? 0.0 : current.getLatitude(),
                                current == null ? 0.0 : current.getLongitude(),
                                current != null && isMock(current))));
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
            report.append("network_type=").append(manager.getNetworkType())
                    .append(" phone_type=").append(manager.getPhoneType())
                    .append(" operator=").append(manager.getNetworkOperator())
                    .append(" operator_name=").append(manager.getNetworkOperatorName())
                    .append(" country=").append(manager.getNetworkCountryIso())
                    .append(" roaming=").append(manager.isNetworkRoaming())
                    .append(" cells=").append(manager.getAllCellInfo() == null ? -1 : manager.getAllCellInfo().size())
                    .append("\n");
        } catch (Throwable throwable) {
            report.append("telephony_error=").append(throwable.getClass().getSimpleName()).append("\n");
        }
    }

    private boolean isMock(Location location) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }
}
