package dev.ghostloc.offline;

import android.bluetooth.BluetoothAdapter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerLoadedParam;

public final class GhostLocModule extends XposedModule {
    private static final String TAG = "GhostLocModule";
    private static final String SELF_PACKAGE = "dev.ghostloc.offline";
    private static volatile XposedInterface bridge;
    private static volatile SharedPreferences prefs;

    public GhostLocModule(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        bridge = base;
        prefs = base.getRemotePreferences(SettingsStore.GROUP);
        log("loaded process=" + param.getProcessName());
    }

    @Override
    public void onSystemServerLoaded(SystemServerLoadedParam param) {
        installLocationManagerHooks();
        installSettingsHooks();
        log("system_server hooks installed");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        installLocationManagerHooks();
        installLocationObjectHooks();
        installSettingsHooks();
        installWifiHooks();
        installTelephonyHooks();
        installBluetoothHooks();
        if (SELF_PACKAGE.equals(packageName)) {
            log("self package observed");
        }
    }

    private static void installLocationManagerHooks() {
        try {
            Class<?> cls = LocationManager.class;
            for (Method method : cls.getDeclaredMethods()) {
                String name = method.getName();
                if ("getLastKnownLocation".equals(name) && method.getReturnType() == Location.class) {
                    bridge.hook(method, LastKnownLocationHooker.class);
                } else if ("requestLocationUpdates".equals(name)) {
                    bridge.hook(method, RequestUpdatesHooker.class);
                } else if ("getCurrentLocation".equals(name)) {
                    bridge.hook(method, RequestUpdatesHooker.class);
                }
            }
        } catch (Throwable throwable) {
            log("LocationManager hook failed", throwable);
        }
    }

    private static void installLocationObjectHooks() {
        try {
            hookBooleanMethod(Location.class, "isFromMockProvider", FalseHooker.class);
            hookBooleanMethod(Location.class, "isMock", FalseHooker.class);
        } catch (Throwable throwable) {
            log("Location object hook failed", throwable);
        }
    }

    private static void installSettingsHooks() {
        try {
            for (Method method : Settings.Secure.class.getDeclaredMethods()) {
                String name = method.getName();
                if ("getString".equals(name) || "getStringForUser".equals(name)) {
                    bridge.hook(method, SecureStringHooker.class);
                } else if ("getInt".equals(name) || "getIntForUser".equals(name)) {
                    bridge.hook(method, SecureIntHooker.class);
                }
            }
        } catch (Throwable throwable) {
            log("Settings.Secure hook failed", throwable);
        }
    }

    private static void installWifiHooks() {
        try {
            for (Method method : WifiManager.class.getDeclaredMethods()) {
                String name = method.getName();
                if ("getScanResults".equals(name)) {
                    bridge.hook(method, EmptyListHooker.class);
                } else if ("isScanAlwaysAvailable".equals(name)) {
                    bridge.hook(method, FalseHooker.class);
                }
            }
        } catch (Throwable throwable) {
            log("Wi-Fi hook failed", throwable);
        }
    }

    private static void installTelephonyHooks() {
        try {
            for (Method method : TelephonyManager.class.getDeclaredMethods()) {
                String name = method.getName();
                if ("getAllCellInfo".equals(name)) {
                    bridge.hook(method, EmptyListHooker.class);
                } else if ("getNetworkType".equals(name)
                        || "getDataNetworkType".equals(name)
                        || "getVoiceNetworkType".equals(name)) {
                    bridge.hook(method, LteHooker.class);
                } else if ("isNetworkRoaming".equals(name)) {
                    bridge.hook(method, FalseHooker.class);
                }
            }
        } catch (Throwable throwable) {
            log("Telephony hook failed", throwable);
        }
    }

    private static void installBluetoothHooks() {
        try {
            Method method = BluetoothAdapter.class.getDeclaredMethod("getAddress");
            bridge.hook(method, BluetoothAddressHooker.class);
        } catch (Throwable throwable) {
            log("Bluetooth hook skipped", throwable);
        }
    }

    private static void hookBooleanMethod(Class<?> cls, String name, Class<? extends XposedInterface.Hooker> hooker) {
        try {
            Method method = cls.getDeclaredMethod(name);
            bridge.hook(method, hooker);
        } catch (NoSuchMethodException ignored) {
        }
    }

    public static final class LastKnownLocationHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!active()) {
                return;
            }
            String provider = providerFrom(callback.getArgs());
            callback.returnAndSkip(makeLocation(provider));
        }
    }

    public static final class RequestUpdatesHooker implements XposedInterface.Hooker {
        public static void after(XposedInterface.AfterHookCallback callback) {
            if (!active()) {
                return;
            }
            Location location = makeLocation(providerFrom(callback.getArgs()));
            for (Object arg : callback.getArgs()) {
                if (arg instanceof LocationListener listener) {
                    try {
                        listener.onLocationChanged(location);
                    } catch (Throwable throwable) {
                        log("listener dispatch failed", throwable);
                    }
                }
            }
        }
    }

    public static final class FalseHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (mockShield()) {
                callback.returnAndSkip(false);
            }
        }
    }

    public static final class EmptyListHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield() || cellShield()) {
                callback.returnAndSkip(Collections.emptyList());
            }
        }
    }

    public static final class LteHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (cellShield()) {
                callback.returnAndSkip(TelephonyManager.NETWORK_TYPE_LTE);
            }
        }
    }

    public static final class BluetoothAddressHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield()) {
                callback.returnAndSkip("02:00:00:00:00:00");
            }
        }
    }

    public static final class SecureStringHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (mockShield() && hasMockLocationKey(callback.getArgs())) {
                callback.returnAndSkip("0");
            }
        }
    }

    public static final class SecureIntHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (mockShield() && hasMockLocationKey(callback.getArgs())) {
                callback.returnAndSkip(0);
            }
        }
    }

    private static Location makeLocation(String provider) {
        SharedPreferences p = prefs();
        Location location = new Location(provider == null ? LocationManager.GPS_PROVIDER : provider);
        double lat = Double.longBitsToDouble(p.getLong(SettingsStore.LAT,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LAT)));
        double lon = Double.longBitsToDouble(p.getLong(SettingsStore.LON,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LON)));
        float speed = p.getFloat(SettingsStore.SPEED, SettingsStore.DEFAULT_SPEED);
        float bearing = p.getFloat(SettingsStore.BEARING, SettingsStore.DEFAULT_BEARING);
        if (p.getBoolean(SettingsStore.MOVEMENT, false)) {
            double[] moved = move(lat, lon, speed, bearing,
                    Math.max(0L, System.currentTimeMillis() - p.getLong(SettingsStore.STARTED_AT, 0L)) / 1000.0);
            lat = moved[0];
            lon = moved[1];
        }
        location.setLatitude(lat);
        location.setLongitude(lon);
        location.setSpeed(speed);
        location.setAltitude(Double.longBitsToDouble(p.getLong(SettingsStore.ALTITUDE,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_ALTITUDE))));
        location.setAccuracy(p.getFloat(SettingsStore.ACCURACY, SettingsStore.DEFAULT_ACCURACY));
        location.setBearing(bearing);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            location.setVerticalAccuracyMeters(5.0f);
            location.setSpeedAccuracyMetersPerSecond(0.1f);
            location.setBearingAccuracyDegrees(1.0f);
        }
        return location;
    }

    private static double[] move(double lat, double lon, float speedMps, float bearingDeg, double seconds) {
        double distance = Math.max(0.0, speedMps) * seconds;
        double radius = 6371000.0;
        double angular = distance / radius;
        double bearing = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(lat);
        double lon1 = Math.toRadians(lon);
        double sinLat1 = Math.sin(lat1);
        double cosLat1 = Math.cos(lat1);
        double sinAngular = Math.sin(angular);
        double cosAngular = Math.cos(angular);
        double lat2 = Math.asin(sinLat1 * cosAngular + cosLat1 * sinAngular * Math.cos(bearing));
        double lon2 = lon1 + Math.atan2(
                Math.sin(bearing) * sinAngular * cosLat1,
                cosAngular - sinLat1 * Math.sin(lat2));
        return new double[]{Math.toDegrees(lat2), normalizeLon(Math.toDegrees(lon2))};
    }

    private static double normalizeLon(double lon) {
        double normalized = ((lon + 540.0) % 360.0) - 180.0;
        return normalized == -180.0 ? 180.0 : normalized;
    }

    private static String providerFrom(Object[] args) {
        if (args == null) {
            return LocationManager.GPS_PROVIDER;
        }
        for (Object arg : args) {
            if (arg instanceof String value
                    && (LocationManager.GPS_PROVIDER.equals(value)
                    || LocationManager.NETWORK_PROVIDER.equals(value)
                    || LocationManager.PASSIVE_PROVIDER.equals(value)
                    || "fused".equals(value))) {
                return value;
            }
        }
        return LocationManager.GPS_PROVIDER;
    }

    private static boolean hasMockLocationKey(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if ("mock_location".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean active() {
        return prefs().getBoolean(SettingsStore.ACTIVE, false);
    }

    private static boolean wifiShield() {
        return prefs().getBoolean(SettingsStore.WIFI_SHIELD, true);
    }

    private static boolean cellShield() {
        return prefs().getBoolean(SettingsStore.CELL_SHIELD, true);
    }

    private static boolean mockShield() {
        return prefs().getBoolean(SettingsStore.MOCK_SHIELD, true);
    }

    private static SharedPreferences prefs() {
        SharedPreferences current = prefs;
        if (current == null && bridge != null) {
            current = bridge.getRemotePreferences(SettingsStore.GROUP);
            prefs = current;
        }
        return current;
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedInterface current = bridge;
        if (current != null) {
            try {
                current.log(TAG + ": " + message);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void log(String message, Throwable throwable) {
        Log.i(TAG, message, throwable);
        XposedInterface current = bridge;
        if (current != null) {
            try {
                current.log(TAG + ": " + message, throwable);
            } catch (Throwable ignored) {
            }
        }
    }
}
