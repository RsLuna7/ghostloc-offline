package dev.ghostloc.offline;

import android.bluetooth.BluetoothAdapter;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.SupplicantState;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerLoadedParam;

public final class GhostLocModule extends XposedModule {
    private static final String TAG = "GhostLocModule";
    private static final String SELF_PACKAGE = "dev.ghostloc.offline";
    private static final String BRIDGE_PROVIDER = "ghostloc";
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
        installGeocoderHooks();
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
                } else if ("getProviders".equals(name) || "getAllProviders".equals(name)) {
                    bridge.hook(method, ProvidersHooker.class);
                } else if ("isProviderEnabled".equals(name)) {
                    bridge.hook(method, ProviderEnabledHooker.class);
                } else if ("getBestProvider".equals(name)) {
                    bridge.hook(method, BestProviderHooker.class);
                } else if ("sendExtraCommand".equals(name)) {
                    bridge.hook(method, ExtraCommandHooker.class);
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

    private static void installGeocoderHooks() {
        try {
            for (Method method : Geocoder.class.getDeclaredMethods()) {
                String name = method.getName();
                if ("getFromLocation".equals(name) || "getFromLocationName".equals(name)) {
                    bridge.hook(method, GeocoderHooker.class);
                }
            }
        } catch (Throwable throwable) {
            log("Geocoder hook failed", throwable);
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
            for (Method method : WifiInfo.class.getDeclaredMethods()) {
                String name = method.getName();
                if ("getMacAddress".equals(name) || "getBSSID".equals(name)) {
                    bridge.hook(method, WifiAddressHooker.class);
                } else if ("getSSID".equals(name)) {
                    bridge.hook(method, WifiSsidHooker.class);
                } else if ("getRssi".equals(name)) {
                    bridge.hook(method, WifiRssiHooker.class);
                } else if ("getLinkSpeed".equals(name)) {
                    bridge.hook(method, WifiLinkSpeedHooker.class);
                } else if ("getFrequency".equals(name)) {
                    bridge.hook(method, WifiFrequencyHooker.class);
                } else if ("getNetworkId".equals(name)) {
                    bridge.hook(method, WifiNetworkIdHooker.class);
                } else if ("getIpAddress".equals(name)) {
                    bridge.hook(method, WifiIpHooker.class);
                } else if ("getSupplicantState".equals(name)) {
                    bridge.hook(method, WifiSupplicantHooker.class);
                } else if ("getHiddenSSID".equals(name)) {
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
                } else if ("getServiceState".equals(name) && method.getReturnType() == ServiceState.class) {
                    bridge.hook(method, ServiceStateHooker.class);
                } else if ("getPhoneType".equals(name)) {
                    bridge.hook(method, PhoneTypeHooker.class);
                } else if ("getNetworkOperator".equals(name)
                        || "getSimOperator".equals(name)) {
                    bridge.hook(method, OperatorCodeHooker.class);
                } else if ("getNetworkOperatorName".equals(name)
                        || "getSimOperatorName".equals(name)) {
                    bridge.hook(method, OperatorNameHooker.class);
                } else if ("getNetworkCountryIso".equals(name)
                        || "getSimCountryIso".equals(name)) {
                    bridge.hook(method, CountryIsoHooker.class);
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
            if (isCancellationSignalSet(callback.getArgs())) {
                return;
            }
            Location location = makeLocation(providerFrom(callback.getArgs()));
            Executor executor = null;
            for (Object arg : callback.getArgs()) {
                if (arg instanceof Executor value) {
                    executor = value;
                }
                if (arg instanceof LocationListener listener) {
                    try {
                        listener.onLocationChanged(location);
                    } catch (Throwable throwable) {
                        log("listener dispatch failed", throwable);
                    }
                }
                if (arg instanceof Consumer<?> consumer) {
                    dispatchConsumer(executor, consumer, location);
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

    public static final class ProvidersHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (active()) {
                callback.returnAndSkip(Arrays.asList(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER,
                        "fused"));
            }
        }
    }

    public static final class ProviderEnabledHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (active() && isKnownProviderArg(callback.getArgs())) {
                callback.returnAndSkip(true);
            }
        }
    }

    public static final class BestProviderHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (active()) {
                callback.returnAndSkip(LocationManager.GPS_PROVIDER);
            }
        }
    }

    public static final class ExtraCommandHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            Object[] args = callback.getArgs();
            if (args == null || args.length < 2 || !BRIDGE_PROVIDER.equals(args[0])) {
                return;
            }
            String command = String.valueOf(args[1]);
            Bundle bundle = null;
            for (Object arg : args) {
                if (arg instanceof Bundle value) {
                    bundle = value;
                }
            }
            handleBridgeCommand(command, bundle);
            callback.returnAndSkip(true);
        }
    }

    public static final class GeocoderHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (active() && mockShield()) {
                Object member = callback.getMember();
                if (member instanceof Method method && method.getReturnType() == Void.TYPE) {
                    dispatchGeocodeListener(callback.getArgs(), makeAddressList());
                    callback.returnAndSkip(null);
                } else {
                    callback.returnAndSkip(makeAddressList());
                }
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

    public static final class PhoneTypeHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (cellShield()) {
                callback.returnAndSkip(TelephonyManager.PHONE_TYPE_GSM);
            }
        }
    }

    public static final class OperatorCodeHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (cellShield()) {
                callback.returnAndSkip("00101");
            }
        }
    }

    public static final class OperatorNameHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (cellShield()) {
                callback.returnAndSkip("GhostLoc");
            }
        }
    }

    public static final class CountryIsoHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (cellShield()) {
                callback.returnAndSkip("us");
            }
        }
    }

    public static final class ServiceStateHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (cellShield()) {
                callback.returnAndSkip(makeServiceState());
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

    public static final class WifiAddressHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield()) {
                callback.returnAndSkip("02:00:00:00:00:00");
            }
        }
    }

    public static final class WifiSsidHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield()) {
                callback.returnAndSkip("\"GhostLoc\"");
            }
        }
    }

    public static final class WifiRssiHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield()) {
                callback.returnAndSkip(-55);
            }
        }
    }

    public static final class WifiLinkSpeedHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield()) {
                callback.returnAndSkip(72);
            }
        }
    }

    public static final class WifiFrequencyHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield()) {
                callback.returnAndSkip(2412);
            }
        }
    }

    public static final class WifiNetworkIdHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield()) {
                callback.returnAndSkip(0);
            }
        }
    }

    public static final class WifiIpHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield()) {
                callback.returnAndSkip(0);
            }
        }
    }

    public static final class WifiSupplicantHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (wifiShield()) {
                callback.returnAndSkip(SupplicantState.COMPLETED);
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

    private static void handleBridgeCommand(String command, Bundle bundle) {
        Bundle data = bundle == null ? Bundle.EMPTY : bundle;
        SharedPreferences.Editor editor = writablePrefsEditor();
        if (editor == null) {
            if ("is_start".equals(command)) {
                data.putBoolean("is_start", active());
            }
            log("bridge command observed=" + command);
            return;
        }
        if ("start".equals(command)) {
            putLocation(editor, data);
            editor.putBoolean(SettingsStore.ACTIVE, true);
            editor.putLong(SettingsStore.STARTED_AT, System.currentTimeMillis());
        } else if ("stop".equals(command)) {
            editor.putBoolean(SettingsStore.ACTIVE, false);
            editor.putLong(SettingsStore.STARTED_AT, 0L);
        } else if ("update_location".equals(command) || "move".equals(command)) {
            putLocation(editor, data);
        } else if ("put_config".equals(command)) {
            putConfig(editor, data);
        } else if ("set_speed".equals(command)) {
            editor.putFloat(SettingsStore.SPEED, (float) data.getDouble("speed", SettingsStore.DEFAULT_SPEED));
        } else if ("set_altitude".equals(command)) {
            editor.putLong(SettingsStore.ALTITUDE,
                    Double.doubleToRawLongBits(data.getDouble("altitude", SettingsStore.DEFAULT_ALTITUDE)));
        } else if ("set_bearing".equals(command)) {
            editor.putFloat(SettingsStore.BEARING, (float) data.getDouble("bearing", SettingsStore.DEFAULT_BEARING));
        } else if ("is_start".equals(command)) {
            data.putBoolean("is_start", active());
        } else {
            return;
        }
        editor.apply();
        log("bridge command handled=" + command);
    }

    private static SharedPreferences.Editor writablePrefsEditor() {
        try {
            return prefs().edit();
        } catch (UnsupportedOperationException unsupported) {
            return null;
        } catch (Throwable throwable) {
            log("prefs editor unavailable", throwable);
            return null;
        }
    }

    private static void putLocation(SharedPreferences.Editor editor, Bundle data) {
        editor.putLong(SettingsStore.LAT,
                Double.doubleToRawLongBits(data.getDouble("lat", currentLat())));
        editor.putLong(SettingsStore.LON,
                Double.doubleToRawLongBits(data.getDouble("lon", currentLon())));
        putConfig(editor, data);
    }

    private static void putConfig(SharedPreferences.Editor editor, Bundle data) {
        if (data.containsKey("speed")) {
            editor.putFloat(SettingsStore.SPEED, (float) data.getDouble("speed", SettingsStore.DEFAULT_SPEED));
        }
        if (data.containsKey("altitude")) {
            editor.putLong(SettingsStore.ALTITUDE,
                    Double.doubleToRawLongBits(data.getDouble("altitude", SettingsStore.DEFAULT_ALTITUDE)));
        }
        if (data.containsKey("accuracy")) {
            editor.putFloat(SettingsStore.ACCURACY, (float) data.getDouble("accuracy", SettingsStore.DEFAULT_ACCURACY));
        }
        if (data.containsKey("bearing")) {
            editor.putFloat(SettingsStore.BEARING, (float) data.getDouble("bearing", SettingsStore.DEFAULT_BEARING));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void dispatchConsumer(Executor executor, Consumer consumer, Location location) {
        Runnable task = () -> {
            try {
                consumer.accept(location);
            } catch (Throwable throwable) {
                log("consumer dispatch failed", throwable);
            }
        };
        if (executor != null) {
            executor.execute(task);
        } else {
            task.run();
        }
    }

    private static List<Address> makeAddressList() {
        SharedPreferences p = prefs();
        double lat = Double.longBitsToDouble(p.getLong(SettingsStore.LAT,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LAT)));
        double lon = Double.longBitsToDouble(p.getLong(SettingsStore.LON,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LON)));
        Address address = new Address(Locale.getDefault());
        address.setLatitude(lat);
        address.setLongitude(lon);
        address.setFeatureName("GhostLoc");
        address.setAddressLine(0, String.format(Locale.US, "GhostLoc %.6f, %.6f", lat, lon));
        ArrayList<Address> result = new ArrayList<>();
        result.add(address);
        return result;
    }

    private static void dispatchGeocodeListener(Object[] args, List<Address> addresses) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            tryInvoke(arg, "onGeocode", new Class<?>[]{List.class}, new Object[]{addresses});
        }
    }

    private static ServiceState makeServiceState() {
        ServiceState serviceState = new ServiceState();
        tryInvoke(serviceState, "setState", new Class<?>[]{int.class}, new Object[]{ServiceState.STATE_IN_SERVICE});
        tryInvoke(serviceState, "setRoaming", new Class<?>[]{boolean.class}, new Object[]{false});
        tryInvoke(serviceState, "setOperatorName",
                new Class<?>[]{String.class, String.class, String.class},
                new Object[]{"GhostLoc", "GhostLoc", "00101"});
        return serviceState;
    }

    private static void tryInvoke(Object target, String methodName, Class<?>[] types, Object[] args) {
        try {
            Method method = target.getClass().getMethod(methodName, types);
            method.invoke(target, args);
        } catch (Throwable ignored) {
        }
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

    private static boolean isKnownProviderArg(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof String value
                    && (LocationManager.GPS_PROVIDER.equals(value)
                    || LocationManager.NETWORK_PROVIDER.equals(value)
                    || LocationManager.PASSIVE_PROVIDER.equals(value)
                    || "fused".equals(value))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCancellationSignalSet(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof CancellationSignal signal && signal.isCanceled()) {
                return true;
            }
        }
        return false;
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

    private static double currentLat() {
        return Double.longBitsToDouble(prefs().getLong(SettingsStore.LAT,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LAT)));
    }

    private static double currentLon() {
        return Double.longBitsToDouble(prefs().getLong(SettingsStore.LON,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LON)));
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
