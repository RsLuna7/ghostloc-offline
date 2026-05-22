package dev.ghostloc.offline;

import android.bluetooth.BluetoothAdapter;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.GnssStatus;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.SupplicantState;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final Set<String> VENDOR_LOCATION_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.android.location.fused",
                    "com.oplus.location",
                    "com.xiaomi.location.fused"
            )));
    private static final int DIAGNOSTIC_LIMIT = 12;
    private static final Set<String> INSTALLED_DEEP_HOOKS = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> LOGGED_HITS = Collections.synchronizedSet(new HashSet<>());
    private static volatile XposedInterface bridge;
    private static volatile SharedPreferences prefs;

    public GhostLocModule(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        bridge = base;
        prefs = base.getRemotePreferences(SettingsStore.GROUP);
        recordProcess(param.getProcessName());
        log("loaded process=" + param.getProcessName());
    }

    @Override
    public void onSystemServerLoaded(SystemServerLoadedParam param) {
        installLocationManagerHooks();
        installDeepLocationServiceHooks(param.getClassLoader());
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
        installVendorLocationHooks(param.getClassLoader(), packageName);
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
                } else if ("getGpsStatus".equals(name)) {
                    bridge.hook(method, GpsStatusHooker.class);
                } else if ("registerGnssStatusCallback".equals(name)
                        || "addGpsStatusListener".equals(name)) {
                    bridge.hook(method, GnssStatusRegistrationHooker.class);
                } else if ("addNmeaListener".equals(name)) {
                    bridge.hook(method, NmeaListenerHooker.class);
                } else if ("sendExtraCommand".equals(name)) {
                    bridge.hook(method, ExtraCommandHooker.class);
                }
            }
        } catch (Throwable throwable) {
            log("LocationManager hook failed", throwable);
        }
    }

    private static void installDeepLocationServiceHooks(ClassLoader loader) {
        hookServiceClass(loader, "com.android.server.location.LocationManagerService", true);
        hookServiceClass(loader, "android.location.ILocationManager$Stub", true);
        hookServiceClass(loader, "com.android.server.location.provider.LocationProviderManager", true);
        hookServiceClass(loader, "com.android.server.location.provider.AbstractLocationProvider", true);
        hookServiceClass(loader, "com.android.server.location.gnss.hal.GnssNative", true);
    }

    private static void installVendorLocationHooks(ClassLoader loader, String packageName) {
        if (!vendorHooksEnabled() || packageName == null || !VENDOR_LOCATION_PACKAGES.contains(packageName)) {
            return;
        }
        log("vendor process observed package=" + packageName);
        hookVendorLocationClass(loader, "com.android.location.fused.FusedLocationProvider");
        hookVendorLocationClass(loader, "com.android.location.fused.FusedLocationProvider$ChildLocationListener");
        hookVendorLocationClass(loader, "com.oplus.location.OplusLocationProvider");
        hookVendorLocationClass(loader, "com.xiaomi.location.fused.XiaomiFusedLocationProvider");
    }

    private static void hookVendorLocationClass(ClassLoader loader, String className) {
        Class<?> cls = findClass(loader, className);
        if (cls == null) {
            log("vendor class missing " + className);
            return;
        }
        int hooks = 0;
        for (Method method : cls.getDeclaredMethods()) {
            String name = method.getName();
            Class<?> returnType = method.getReturnType();
            Class<? extends XposedInterface.Hooker> hooker = null;
            if ("chooseBestLocation".equals(name) && returnType == Location.class) {
                hooker = VendorLocationReturnHooker.class;
            } else if ("onLocationChanged".equals(name)) {
                hooker = VendorLocationArgHooker.class;
            } else if ("reportBestLocationLocked".equals(name)
                    || "onSetRequest".equals(name)
                    || "onFlush".equals(name)
                    || "start".equals(name)
                    || "stop".equals(name)) {
                hooker = VendorTraceHooker.class;
            }
            if (hooker != null && hookMethodOnce(method, hooker)) {
                hooks++;
                log("vendor method " + cls.getName() + "#" + methodSignature(method));
            }
        }
        log("vendor hooks " + className + " installed=" + hooks);
    }

    private static void hookServiceClass(ClassLoader loader, String className, boolean discover) {
        Class<?> cls = findClass(loader, className);
        if (cls == null) {
            log("deep class missing " + className);
            return;
        }
        if (discover) {
            logTargetMethods(cls);
        }
        int hooks = 0;
        for (Method method : cls.getDeclaredMethods()) {
            String name = method.getName();
            Class<?> returnType = method.getReturnType();
            Class<? extends XposedInterface.Hooker> hooker = null;
            if ("getLastLocation".equals(name) && returnType == Location.class) {
                hooker = ServiceLocationReturnHooker.class;
            } else if ("getCurrentLocation".equals(name)) {
                hooker = ServiceCurrentLocationHooker.class;
            } else if ("requestLocationUpdates".equals(name)
                    || "registerLocationListener".equals(name)) {
                hooker = ServiceListenerHooker.class;
            } else if ("removeUpdates".equals(name)) {
                hooker = ServiceTraceHooker.class;
            } else if ("reportLocation".equals(name)) {
                hooker = ServiceReportLocationHooker.class;
            } else if ("onTransact".equals(name) && "android.location.ILocationManager$Stub".equals(className)) {
                hooker = ServiceTraceHooker.class;
            }
            if (hooker != null && hookMethodOnce(method, hooker)) {
                hooks++;
            }
        }
        log("deep hooks " + className + " installed=" + hooks);
    }

    private static Class<?> findClass(ClassLoader loader, String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable first) {
            try {
                return Class.forName(className);
            } catch (Throwable second) {
                return null;
            }
        }
    }

    private static boolean hookMethodOnce(Method method, Class<? extends XposedInterface.Hooker> hooker) {
        String key = method.getDeclaringClass().getName() + "#" + methodSignature(method);
        if (!INSTALLED_DEEP_HOOKS.add(key)) {
            return false;
        }
        try {
            method.setAccessible(true);
            bridge.hook(method, hooker);
            log("deep hook installed " + key);
            return true;
        } catch (Throwable throwable) {
            INSTALLED_DEEP_HOOKS.remove(key);
            log("deep hook skipped " + key, throwable);
            return false;
        }
    }

    private static void logTargetMethods(Class<?> cls) {
        try {
            for (Method method : cls.getDeclaredMethods()) {
                String name = method.getName();
                if ("getLastLocation".equals(name)
                        || "requestLocationUpdates".equals(name)
                        || "getCurrentLocation".equals(name)
                        || "registerLocationListener".equals(name)
                        || "removeUpdates".equals(name)
                        || "reportLocation".equals(name)
                        || "onTransact".equals(name)) {
                    log("deep method " + cls.getName() + "#" + methodSignature(method));
                }
            }
        } catch (Throwable throwable) {
            log("deep method discovery failed " + cls.getName(), throwable);
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
                    bridge.hook(method, CellInfoHooker.class);
                } else if ("requestCellInfoUpdate".equals(name)) {
                    bridge.hook(method, RequestCellInfoUpdateHooker.class);
                } else if ("getNetworkType".equals(name)
                        || "getDataNetworkType".equals(name)
                        || "getVoiceNetworkType".equals(name)) {
                    bridge.hook(method, NetworkTypeHooker.class);
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

    public static final class ServiceLocationReturnHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!active()) {
                return;
            }
            logHit(callback, "service return");
            callback.returnAndSkip(makeLocation(providerFrom(callback.getArgs())));
        }
    }

    public static final class ServiceCurrentLocationHooker implements XposedInterface.Hooker {
        public static void after(XposedInterface.AfterHookCallback callback) {
            if (!active() || callback.getThrowable() != null) {
                return;
            }
            logHit(callback, "service current");
            dispatchLocationCallbacks(callback.getArgs(), makeLocation(providerFrom(callback.getArgs())));
        }
    }

    public static final class ServiceListenerHooker implements XposedInterface.Hooker {
        public static void after(XposedInterface.AfterHookCallback callback) {
            if (!active() || callback.getThrowable() != null) {
                return;
            }
            logHit(callback, "service listener");
            dispatchLocationCallbacks(callback.getArgs(), makeLocation(providerFrom(callback.getArgs())));
        }
    }

    public static final class ServiceReportLocationHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!active()) {
                return;
            }
            Object[] args = callback.getArgs();
            if (args == null) {
                return;
            }
            boolean replaced = false;
            Location location = makeLocation(providerFrom(args));
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Location) {
                    args[i] = location;
                    replaced = true;
                } else {
                    Object result = replaceLocationResult(arg, location);
                    if (result != arg) {
                        args[i] = result;
                        replaced = true;
                    }
                }
            }
            if (replaced) {
                logHit(callback, "service report replaced");
            } else {
                logHit(callback, "service report observed");
            }
        }
    }

    public static final class ServiceTraceHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            logHit(callback, "service trace uid=" + Binder.getCallingUid());
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

    public static final class GpsStatusHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!active()) {
                return;
            }
            callback.returnAndSkip(makeGpsStatus());
        }
    }

    public static final class GnssStatusRegistrationHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!active()) {
                return;
            }
            Object member = callback.getMember();
            String name = member instanceof Method method ? method.getName() : "";
            boolean handled;
            if ("addGpsStatusListener".equals(name)) {
                handled = dispatchLegacyGpsStatus(callback.getArgs());
            } else {
                handled = dispatchGnssStatusRegistration(callback.getArgs());
            }
            if (!handled) {
                return;
            }
            callback.returnAndSkip(true);
        }
    }

    public static final class NmeaListenerHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!active()) {
                return;
            }
            if (dispatchNmeaListeners(callback.getArgs())) {
                callback.returnAndSkip(true);
            }
        }
    }

    public static final class VendorLocationReturnHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!active()) {
                return;
            }
            logHit(callback, "vendor location");
            callback.returnAndSkip(makeLocation(LocationManager.FUSED_PROVIDER));
        }
    }

    public static final class VendorLocationArgHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!active()) {
                return;
            }
            Object[] args = callback.getArgs();
            if (args == null) {
                return;
            }
            Location location = makeLocation(LocationManager.FUSED_PROVIDER);
            boolean replaced = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Location) {
                    args[i] = location;
                    replaced = true;
                }
            }
            if (replaced) {
                logHit(callback, "vendor callback");
            }
        }
    }

    public static final class VendorTraceHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            logHit(callback, "vendor trace");
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

    public static final class CellInfoHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (cellShield()) {
                callback.returnAndSkip(makeCellInfoList());
            }
        }
    }

    public static final class RequestCellInfoUpdateHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!cellShield()) {
                return;
            }
            List<CellInfo> cells = makeCellInfoList();
            if (dispatchCellInfoCallback(callback.getArgs(), cells)) {
                logHit(callback, "cell callback");
                callback.returnAndSkip(null);
            }
        }
    }

    public static final class NetworkTypeHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (!cellShield()) {
                return;
            }
            CellProfile profile = currentCellProfile();
            Object member = callback.getMember();
            String name = member instanceof Method method ? method.getName() : "";
            int value;
            if ("getVoiceNetworkType".equals(name)) {
                value = TelephonyManager.NETWORK_TYPE_LTE;
            } else if (profile.nrEnabled) {
                value = TelephonyManager.NETWORK_TYPE_NR;
            } else {
                value = TelephonyManager.NETWORK_TYPE_LTE;
            }
            callback.returnAndSkip(value);
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
                callback.returnAndSkip(currentCellProfile().operatorNumeric());
            }
        }
    }

    public static final class OperatorNameHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (cellShield()) {
                callback.returnAndSkip(currentCellProfile().operatorName);
            }
        }
    }

    public static final class CountryIsoHooker implements XposedInterface.Hooker {
        public static void before(XposedInterface.BeforeHookCallback callback) {
            if (cellShield()) {
                callback.returnAndSkip(currentCellProfile().countryIso);
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
        LocationState state = currentLocationState(provider == null ? LocationManager.GPS_PROVIDER : provider, p);
        Location location = new Location(state.provider);
        location.setLatitude(state.latitude);
        location.setLongitude(state.longitude);
        location.setSpeed(state.speed);
        location.setAltitude(state.altitude);
        location.setAccuracy(state.accuracy);
        location.setBearing(state.bearing);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            location.setVerticalAccuracyMeters(5.0f);
            location.setSpeedAccuracyMetersPerSecond(0.1f);
            location.setBearingAccuracyDegrees(1.0f);
        }
        return location;
    }

    private static LocationState currentLocationState(String provider, SharedPreferences preferences) {
        double lat = Double.longBitsToDouble(preferences.getLong(SettingsStore.LAT,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LAT)));
        double lon = Double.longBitsToDouble(preferences.getLong(SettingsStore.LON,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LON)));
        float baseSpeed = preferences.getFloat(SettingsStore.SPEED, SettingsStore.DEFAULT_SPEED);
        float baseBearing = preferences.getFloat(SettingsStore.BEARING, SettingsStore.DEFAULT_BEARING);
        double baseAltitude = Double.longBitsToDouble(preferences.getLong(SettingsStore.ALTITUDE,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_ALTITUDE)));
        float baseAccuracy = preferences.getFloat(SettingsStore.ACCURACY, SettingsStore.DEFAULT_ACCURACY);
        double elapsedSeconds = routeElapsedSeconds(preferences);
        float speedVariation = preferences.getFloat(SettingsStore.ROUTE_SPEED_VARIATION,
                SettingsStore.DEFAULT_ROUTE_SPEED_VARIATION);
        float effectiveSpeed = variedSpeed(baseSpeed, speedVariation, elapsedSeconds);
        if (preferences.getBoolean(SettingsStore.MOVEMENT, false)) {
            List<RoutePoint> route = parseRoutePoints(preferences.getString(SettingsStore.ROUTE_POINTS, ""));
            if (route.size() >= 2) {
                LocationState routeState = routeLocationState(
                        provider,
                        preferences,
                        route,
                        elapsedSeconds,
                        baseSpeed,
                        speedVariation,
                        preferences.getBoolean(SettingsStore.ROUTE_LOOP, false),
                        preferences.getBoolean(SettingsStore.ROUTE_AUTO_BEARING, true),
                        baseBearing,
                        baseAltitude,
                        baseAccuracy);
                if (routeState != null) {
                    return routeState;
                }
            }
            double[] moved = moveDistance(lat, lon, baseBearing,
                    integratedDistanceMeters(baseSpeed, speedVariation, elapsedSeconds));
            lat = moved[0];
            lon = moved[1];
        }
        return new LocationState(
                provider,
                lat,
                lon,
                effectiveSpeed,
                applyAltitudeJitter(preferences, baseAltitude, elapsedSeconds),
                applyAccuracyJitter(preferences, baseAccuracy, elapsedSeconds),
                baseBearing);
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

    private static void dispatchLocationCallbacks(Object[] args, Location location) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            if (arg instanceof LocationListener listener) {
                try {
                    listener.onLocationChanged(location);
                } catch (Throwable throwable) {
                    log("service listener dispatch failed", throwable);
                }
                continue;
            }
            if (tryDispatchRemoteLocation(arg, location)) {
                continue;
            }
            tryDispatchRemoteLocationList(arg, location);
        }
    }

    private static boolean tryDispatchRemoteLocation(Object target, Location location) {
        String[] names = {"onLocation", "onLocationChanged"};
        for (String name : names) {
            if (tryInvoke(target, name, new Class<?>[]{Location.class}, new Object[]{location})) {
                return true;
            }
        }
        return false;
    }

    private static boolean tryDispatchRemoteLocationList(Object target, Location location) {
        Object locationResult = makeLocationResult(location);
        if (locationResult != null
                && tryInvoke(target, "onLocationChanged",
                new Class<?>[]{locationResult.getClass()},
                new Object[]{locationResult})) {
            return true;
        }
        return tryInvoke(target, "onLocationChanged", new Class<?>[]{List.class},
                new Object[]{Collections.singletonList(location)});
    }

    private static Object replaceLocationResult(Object candidate, Location location) {
        if (candidate == null) {
            return candidate;
        }
        if (!"android.location.LocationResult".equals(candidate.getClass().getName())) {
            return candidate;
        }
        Object replacement = makeLocationResult(location);
        return replacement == null ? candidate : replacement;
    }

    private static Object makeLocationResult(Location location) {
        Class<?> cls = findClass(GhostLocModule.class.getClassLoader(), "android.location.LocationResult");
        if (cls == null) {
            return null;
        }
        List<Location> locations = Collections.singletonList(location);
        Object result = tryStaticInvoke(cls, "wrap", new Class<?>[]{List.class}, new Object[]{locations});
        if (result != null) {
            return result;
        }
        result = tryStaticInvoke(cls, "create", new Class<?>[]{List.class}, new Object[]{locations});
        if (result != null) {
            return result;
        }
        try {
            Method method = cls.getDeclaredMethod("create", Location[].class);
            method.setAccessible(true);
            return method.invoke(null, new Object[]{new Location[]{location}});
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static GpsStatus makeGpsStatus() {
        GnssStatus gnssStatus = makeGnssStatus();
        if (gnssStatus == null) {
            return null;
        }
        try {
            return GpsStatus.create(gnssStatus, 1100);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static GnssStatus makeGnssStatus() {
        GnssProfile profile = currentGnssProfile();
        GnssStatus status = newInstance(GnssStatus.class);
        if (status == null) {
            return null;
        }
        int count = profile.satellites.length;
        int[] svidWithFlags = new int[count];
        float[] cn0 = new float[count];
        float[] elevations = new float[count];
        float[] azimuths = new float[count];
        float[] carrierFrequencies = new float[count];
        float[] basebandCn0 = new float[count];
        for (int i = 0; i < count; i++) {
            GnssSatelliteProfile satellite = profile.satellites[i];
            svidWithFlags[i] = encodeGnssSvidWithFlags(satellite);
            cn0[i] = satellite.cn0DbHz;
            elevations[i] = satellite.elevationDegrees;
            azimuths[i] = satellite.azimuthDegrees;
            carrierFrequencies[i] = satellite.carrierFrequencyHz;
            basebandCn0[i] = satellite.cn0DbHz - 1.5f;
        }
        setFieldIfPresent(status, "mSvCount", count);
        setFieldIfPresent(status, "mSvidWithFlags", svidWithFlags);
        setFieldIfPresent(status, "mCn0DbHzs", cn0);
        setFieldIfPresent(status, "mElevations", elevations);
        setFieldIfPresent(status, "mAzimuths", azimuths);
        setFieldIfPresent(status, "mCarrierFrequencies", carrierFrequencies);
        setFieldIfPresent(status, "mBasebandCn0DbHzs", basebandCn0);
        return status;
    }

    private static int encodeGnssSvidWithFlags(GnssSatelliteProfile satellite) {
        int flags = 0x1 | 0x2 | 0x8 | 0x10;
        if (satellite.usedInFix) {
            flags |= 0x4;
        }
        return (satellite.svid << 8) | (satellite.constellationType << 4) | flags;
    }

    private static GnssProfile currentGnssProfile() {
        SharedPreferences sharedPreferences = prefs();
        Location location = makeLocation(LocationManager.GPS_PROVIDER);
        long seed = mixSeed(location.getLatitude(), location.getLongitude(),
                "gnss", Integer.toString(sharedPreferences.getInt(
                        SettingsStore.GNSS_SAT_COUNT, SettingsStore.DEFAULT_GNSS_SAT_COUNT)), "profile");
        int satelliteCount = Math.max(4, Math.min(24,
                sharedPreferences.getInt(SettingsStore.GNSS_SAT_COUNT, SettingsStore.DEFAULT_GNSS_SAT_COUNT)));
        GnssSatelliteProfile[] satellites = new GnssSatelliteProfile[satelliteCount];
        for (int i = 0; i < satelliteCount; i++) {
            long itemSeed = Long.rotateLeft(seed, i + 3) ^ (i * 0x9e3779b9L);
            int constellationType;
            if (i % 6 == 4) {
                constellationType = GnssStatus.CONSTELLATION_BEIDOU;
            } else if (i % 6 == 5) {
                constellationType = GnssStatus.CONSTELLATION_GLONASS;
            } else {
                constellationType = GnssStatus.CONSTELLATION_GPS;
            }
            int svid = 3 + positiveInt(itemSeed, 28) + i;
            float cn0 = 28.0f + positiveInt(Long.rotateLeft(itemSeed, 7), 18);
            float elevation = 12.0f + positiveInt(Long.rotateLeft(itemSeed, 11), 70);
            float azimuth = positiveInt(Long.rotateLeft(itemSeed, 13), 360);
            boolean usedInFix = i < Math.min(8, satelliteCount);
            float carrierFrequency = constellationType == GnssStatus.CONSTELLATION_GLONASS
                    ? 1_602_000_000f
                    : 1_575_420_000f;
            satellites[i] = new GnssSatelliteProfile(
                    constellationType,
                    svid,
                    cn0,
                    elevation,
                    azimuth,
                    carrierFrequency,
                    usedInFix
            );
        }
        return new GnssProfile(
                satellites,
                sharedPreferences.getBoolean(SettingsStore.NMEA_ENABLED, true),
                sharedPreferences.getBoolean(SettingsStore.AGPS_ENABLED, true)
        );
    }

    private static boolean dispatchGnssStatusRegistration(Object[] args) {
        if (args == null) {
            return false;
        }
        Executor executor = null;
        Handler handler = null;
        Object callback = null;
        for (Object arg : args) {
            if (arg instanceof Executor value) {
                executor = value;
            } else if (arg instanceof Handler value) {
                handler = value;
            } else if (arg != null && hasMethod(arg.getClass(), "onSatelliteStatusChanged", GnssStatus.class)) {
                callback = arg;
            }
        }
        if (callback == null) {
            return false;
        }
        GnssStatus status = makeGnssStatus();
        final Object callbackTarget = callback;
        dispatchAsync(executor, handler, () -> {
            tryInvoke(callbackTarget, "onStarted", new Class<?>[0], new Object[0]);
            tryInvoke(callbackTarget, "onFirstFix", new Class<?>[]{int.class}, new Object[]{1100});
            if (status != null) {
                tryInvoke(callbackTarget, "onSatelliteStatusChanged",
                        new Class<?>[]{GnssStatus.class}, new Object[]{status});
            }
        });
        return true;
    }

    private static boolean dispatchLegacyGpsStatus(Object[] args) {
        if (args == null) {
            return false;
        }
        Object listener = null;
        for (Object arg : args) {
            if (arg != null && hasMethod(arg.getClass(), "onGpsStatusChanged", int.class)) {
                listener = arg;
                break;
            }
        }
        if (listener == null) {
            return false;
        }
        final Object callbackTarget = listener;
        dispatchAsync(null, null, () -> {
            tryInvoke(callbackTarget, "onGpsStatusChanged",
                    new Class<?>[]{int.class}, new Object[]{GpsStatus.GPS_EVENT_STARTED});
            tryInvoke(callbackTarget, "onGpsStatusChanged",
                    new Class<?>[]{int.class}, new Object[]{GpsStatus.GPS_EVENT_FIRST_FIX});
            tryInvoke(callbackTarget, "onGpsStatusChanged",
                    new Class<?>[]{int.class}, new Object[]{GpsStatus.GPS_EVENT_SATELLITE_STATUS});
        });
        return true;
    }

    private static boolean dispatchNmeaListeners(Object[] args) {
        if (args == null) {
            return false;
        }
        Executor executor = null;
        Handler handler = null;
        Object listener = null;
        for (Object arg : args) {
            if (arg instanceof Executor value) {
                executor = value;
            } else if (arg instanceof Handler value) {
                handler = value;
            } else if (arg instanceof OnNmeaMessageListener
                    || (arg != null && (hasMethod(arg.getClass(), "onNmeaMessage", String.class, long.class)
                    || hasMethod(arg.getClass(), "onNmeaReceived", long.class, String.class)))) {
                listener = arg;
            }
        }
        if (listener == null) {
            return false;
        }
        GnssProfile profile = currentGnssProfile();
        if (!profile.nmeaEnabled) {
            return true;
        }
        List<String> sentences = makeNmeaSentences(makeLocation(LocationManager.GPS_PROVIDER), profile);
        final Object listenerTarget = listener;
        dispatchAsync(executor, handler, () -> {
            long timestamp = System.currentTimeMillis();
            for (String sentence : sentences) {
                if (!tryInvoke(listenerTarget, "onNmeaMessage",
                        new Class<?>[]{String.class, long.class}, new Object[]{sentence, timestamp})) {
                    tryInvoke(listenerTarget, "onNmeaReceived",
                            new Class<?>[]{long.class, String.class}, new Object[]{timestamp, sentence});
                }
            }
        });
        return true;
    }

    private static void dispatchAsync(Executor executor, Handler handler, Runnable task) {
        if (executor != null) {
            executor.execute(task);
        } else if (handler != null) {
            handler.post(task);
        } else {
            task.run();
        }
    }

    private static List<String> makeNmeaSentences(Location location, GnssProfile profile) {
        ArrayList<String> sentences = new ArrayList<>();
        long now = System.currentTimeMillis();
        String utcTime = nmeaUtcTime(now);
        String utcDate = nmeaUtcDate(now);
        String lat = nmeaCoordinate(Math.abs(location.getLatitude()), true);
        String latHemisphere = location.getLatitude() >= 0 ? "N" : "S";
        String lon = nmeaCoordinate(Math.abs(location.getLongitude()), false);
        String lonHemisphere = location.getLongitude() >= 0 ? "E" : "W";
        sentences.add(withNmeaChecksum(String.format(Locale.US,
                "$GPGGA,%s,%s,%s,%s,%s,1,%02d,0.9,%.1f,M,0.0,M,,",
                utcTime, lat, latHemisphere, lon, lonHemisphere, profile.satellites.length,
                location.getAltitude())));
        sentences.add(withNmeaChecksum(String.format(Locale.US,
                "$GPRMC,%s,A,%s,%s,%s,%s,%.1f,%.1f,%s,,",
                utcTime, lat, latHemisphere, lon, lonHemisphere,
                location.getSpeed() * 1.9438445f, location.getBearing(), utcDate)));
        sentences.add(withNmeaChecksum(String.format(Locale.US,
                "$GPGSA,A,3,03,07,09,16,19,22,26,28,31,14,18,1.2,0.9,1.5",
                utcTime)));
        int totalMessages = (profile.satellites.length + 3) / 4;
        for (int messageIndex = 0; messageIndex < totalMessages; messageIndex++) {
            StringBuilder builder = new StringBuilder("$GPGSV,")
                    .append(totalMessages).append(',')
                    .append(messageIndex + 1).append(',')
                    .append(String.format(Locale.US, "%02d", profile.satellites.length));
            for (int i = messageIndex * 4; i < Math.min(profile.satellites.length, (messageIndex + 1) * 4); i++) {
                GnssSatelliteProfile satellite = profile.satellites[i];
                builder.append(',').append(String.format(Locale.US, "%02d", satellite.svid))
                        .append(',').append(String.format(Locale.US, "%02.0f", satellite.elevationDegrees))
                        .append(',').append(String.format(Locale.US, "%03.0f", satellite.azimuthDegrees))
                        .append(',').append(String.format(Locale.US, "%02.0f", satellite.cn0DbHz));
            }
            sentences.add(withNmeaChecksum(builder.toString()));
        }
        return sentences;
    }

    private static String nmeaUtcTime(long epochMillis) {
        java.util.Calendar calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(epochMillis);
        return String.format(Locale.US, "%02d%02d%02d.00",
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE),
                calendar.get(java.util.Calendar.SECOND));
    }

    private static String nmeaUtcDate(long epochMillis) {
        java.util.Calendar calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(epochMillis);
        return String.format(Locale.US, "%02d%02d%02d",
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.YEAR) % 100);
    }

    private static String nmeaCoordinate(double absoluteValue, boolean latitude) {
        int degrees = (int) absoluteValue;
        double minutes = (absoluteValue - degrees) * 60.0;
        return latitude
                ? String.format(Locale.US, "%02d%07.4f", degrees, minutes)
                : String.format(Locale.US, "%03d%07.4f", degrees, minutes);
    }

    private static String withNmeaChecksum(String sentence) {
        int checksum = 0;
        for (int i = 1; i < sentence.length(); i++) {
            checksum ^= sentence.charAt(i);
        }
        return sentence + '*' + String.format(Locale.US, "%02X", checksum);
    }

    private static List<CellInfo> makeCellInfoList() {
        CellProfile profile = currentCellProfile();
        ArrayList<CellInfo> cells = new ArrayList<>();
        CellInfoLte lte = makeLteCellInfo(profile);
        if (lte != null) {
            cells.add(lte);
        }
        if (profile.nrEnabled) {
            CellInfoNr nr = makeNrCellInfo(profile);
            if (nr != null) {
                cells.add(nr);
            }
        }
        return cells;
    }

    private static CellProfile currentCellProfile() {
        SharedPreferences sharedPreferences = prefs();
        double lat = currentLat();
        double lon = currentLon();
        CarrierPreset preset = carrierPresetFor(lat, lon);
        String mcc = stringPreference(sharedPreferences, SettingsStore.CELL_MCC, preset.mcc);
        String mnc = stringPreference(sharedPreferences, SettingsStore.CELL_MNC, preset.mnc);
        String countryIso = stringPreference(sharedPreferences, SettingsStore.CELL_COUNTRY_ISO, preset.countryIso);
        String operatorName = stringPreference(sharedPreferences, SettingsStore.CELL_OPERATOR_NAME, preset.operatorName);
        boolean nrEnabled = android.os.Build.VERSION.SDK_INT >= 29
                && sharedPreferences.getBoolean(SettingsStore.CELL_ENABLE_NR, true);
        long seed = mixSeed(lat, lon, mcc, mnc, operatorName);
        int lteTac = 1000 + positiveInt(seed, 60000 - 1000);
        int lteCi = 1 + positiveInt(Long.rotateLeft(seed, 7), 0x0ffffffe);
        int ltePci = positiveInt(Long.rotateLeft(seed, 13), 503);
        int lteEarfcn = 1200 + positiveInt(Long.rotateLeft(seed, 17), 3200);
        int[] lteBands = new int[]{preset.lteBand};
        int lteBandwidth = preset.lteBandwidthKhz;
        int lteRsrp = -88 - positiveInt(Long.rotateLeft(seed, 19), 18);
        int lteRsrq = -8 - positiveInt(Long.rotateLeft(seed, 23), 8);
        int lteRssnr = 50 + positiveInt(Long.rotateLeft(seed, 29), 160);
        int lteRssi = -62 - positiveInt(Long.rotateLeft(seed, 31), 18);
        int lteTimingAdvance = 1 + positiveInt(Long.rotateLeft(seed, 37), 64);
        long nrNci = 1L + positiveLong(Long.rotateLeft(seed, 41), 68_719_476_734L);
        int nrTac = 2000 + positiveInt(Long.rotateLeft(seed, 43), 60000 - 2000);
        int nrPci = positiveInt(Long.rotateLeft(seed, 47), 1008);
        int nrArfcn = preset.nrArfcnBase + positiveInt(Long.rotateLeft(seed, 53), 20000);
        int[] nrBands = new int[]{preset.nrBand};
        int nrSsRsrp = -86 - positiveInt(Long.rotateLeft(seed, 59), 20);
        int nrSsRsrq = -10 - positiveInt(Long.rotateLeft(seed, 61), 8);
        int nrSsSinr = 30 + positiveInt(Long.rotateLeft(seed, 5), 140);
        int nrCsiRsrp = nrSsRsrp + 2;
        int nrCsiRsrq = nrSsRsrq + 1;
        int nrCsiSinr = nrSsSinr + 5;
        int nrTimingAdvance = 10 + positiveInt(Long.rotateLeft(seed, 11), 120);
        return new CellProfile(
                mcc,
                mnc,
                countryIso,
                operatorName,
                nrEnabled,
                lteTac,
                lteCi,
                ltePci,
                lteEarfcn,
                lteBands,
                lteBandwidth,
                lteRssi,
                lteRsrp,
                lteRsrq,
                lteRssnr,
                lteTimingAdvance,
                nrTac,
                nrNci,
                nrPci,
                nrArfcn,
                nrBands,
                nrSsRsrp,
                nrSsRsrq,
                nrSsSinr,
                nrCsiRsrp,
                nrCsiRsrq,
                nrCsiSinr,
                nrTimingAdvance
        );
    }

    private static CellInfoLte makeLteCellInfo(CellProfile profile) {
        CellInfoLte info = newInstance(CellInfoLte.class);
        if (info == null) {
            log("CellInfoLte ctor unavailable");
            return null;
        }
        applyBaseCellInfo(info, true, CellInfo.CONNECTION_PRIMARY_SERVING);
        CellIdentityLte identity = newInstance(CellIdentityLte.class);
        CellSignalStrengthLte signal = newInstance(CellSignalStrengthLte.class);
        if (identity == null || signal == null) {
            log("LTE identity/signal ctor unavailable");
            return info;
        }
        applyBaseCellIdentity(identity, profile, "CellIdentityLte");
        setFieldIfPresent(identity, "mCi", profile.lteCi);
        setFieldIfPresent(identity, "mPci", profile.ltePci);
        setFieldIfPresent(identity, "mTac", profile.lteTac);
        setFieldIfPresent(identity, "mEarfcn", profile.lteEarfcn);
        setFieldIfPresent(identity, "mBandwidth", profile.lteBandwidth);
        setFieldIfPresent(identity, "mBands", profile.lteBands);
        setFieldIfPresent(identity, "mAdditionalPlmns", new ArraySet<String>());
        setFieldIfPresent(signal, "mRssi", profile.lteRssi);
        setFieldIfPresent(signal, "mRsrp", profile.lteRsrp);
        setFieldIfPresent(signal, "mRsrq", profile.lteRsrq);
        setFieldIfPresent(signal, "mRssnr", profile.lteRssnr);
        setFieldIfPresent(signal, "mCqi", 9);
        setFieldIfPresent(signal, "mCqiTableIndex", 1);
        setFieldIfPresent(signal, "mTimingAdvance", profile.lteTimingAdvance);
        setFieldIfPresent(signal, "mLevel", 4);
        setFieldIfPresent(signal, "mParametersUseForLevel", 1);
        setFieldIfPresent(info, "mCellIdentityLte", identity);
        setFieldIfPresent(info, "mCellSignalStrengthLte", signal);
        return info;
    }

    private static CellInfoNr makeNrCellInfo(CellProfile profile) {
        CellInfoNr info = newInstance(CellInfoNr.class);
        if (info == null) {
            log("CellInfoNr ctor unavailable");
            return null;
        }
        applyBaseCellInfo(info, false, CellInfo.CONNECTION_SECONDARY_SERVING);
        CellIdentityNr identity = newInstance(CellIdentityNr.class);
        CellSignalStrengthNr signal = newInstance(CellSignalStrengthNr.class);
        if (identity == null || signal == null) {
            log("NR identity/signal ctor unavailable");
            return info;
        }
        applyBaseCellIdentity(identity, profile, "CellIdentityNr");
        setFieldIfPresent(identity, "mNci", profile.nrNci);
        setFieldIfPresent(identity, "mPci", profile.nrPci);
        setFieldIfPresent(identity, "mTac", profile.nrTac);
        setFieldIfPresent(identity, "mNrArfcn", profile.nrArfcn);
        setFieldIfPresent(identity, "mBands", profile.nrBands);
        setFieldIfPresent(identity, "mAdditionalPlmns", new ArraySet<String>());
        setFieldIfPresent(signal, "mSsRsrp", profile.nrSsRsrp);
        setFieldIfPresent(signal, "mSsRsrq", profile.nrSsRsrq);
        setFieldIfPresent(signal, "mSsSinr", profile.nrSsSinr);
        setFieldIfPresent(signal, "mCsiRsrp", profile.nrCsiRsrp);
        setFieldIfPresent(signal, "mCsiRsrq", profile.nrCsiRsrq);
        setFieldIfPresent(signal, "mCsiSinr", profile.nrCsiSinr);
        setFieldIfPresent(signal, "mTimingAdvance", profile.nrTimingAdvance);
        setFieldIfPresent(signal, "mLevel", 4);
        setFieldIfPresent(signal, "mParametersUseForLevel", 1);
        setFieldIfPresent(info, "mCellIdentity", identity);
        setFieldIfPresent(info, "mCellSignalStrength", signal);
        return info;
    }

    private static void applyBaseCellInfo(CellInfo cellInfo, boolean registered, int connectionStatus) {
        tryInvoke(cellInfo, "setRegistered", new Class<?>[]{boolean.class}, new Object[]{registered});
        tryInvoke(cellInfo, "setTimeStamp", new Class<?>[]{long.class}, new Object[]{System.currentTimeMillis()});
        tryInvoke(cellInfo, "setCellConnectionStatus", new Class<?>[]{int.class}, new Object[]{connectionStatus});
        setFieldIfPresent(cellInfo, "mRegistered", registered);
        setFieldIfPresent(cellInfo, "mTimeStamp", System.currentTimeMillis());
        setFieldIfPresent(cellInfo, "mCellConnectionStatus", connectionStatus);
    }

    private static void applyBaseCellIdentity(Object identity, CellProfile profile, String tag) {
        setFieldIfPresent(identity, "mTag", tag);
        setFieldIfPresent(identity, "mType", tag.endsWith("Nr") ? 6 : 3);
        setFieldIfPresent(identity, "mMccStr", profile.mcc);
        setFieldIfPresent(identity, "mMncStr", profile.mnc);
        setFieldIfPresent(identity, "mAlphaLong", profile.operatorName);
        setFieldIfPresent(identity, "mAlphaShort", profile.operatorName);
        setFieldIfPresent(identity, "mGlobalCellId", profile.operatorNumeric());
    }

    private static boolean dispatchCellInfoCallback(Object[] args, List<CellInfo> cells) {
        if (args == null) {
            return false;
        }
        Executor executor = null;
        Object callback = null;
        for (Object arg : args) {
            if (arg instanceof Executor value) {
                executor = value;
            } else if (arg != null && hasMethod(arg.getClass(), "onCellInfo", List.class)) {
                callback = arg;
            }
        }
        if (callback == null) {
            return false;
        }
        final Object callbackTarget = callback;
        Runnable task = () -> {
            if (!tryInvoke(callbackTarget, "onCellInfo", new Class<?>[]{List.class}, new Object[]{cells})) {
                log("cell callback dispatch failed " + callbackTarget.getClass().getName());
            }
        };
        if (executor != null) {
            executor.execute(task);
        } else {
            task.run();
        }
        return true;
    }

    private static ServiceState makeServiceState() {
        CellProfile profile = currentCellProfile();
        ServiceState serviceState = new ServiceState();
        tryInvoke(serviceState, "setState", new Class<?>[]{int.class}, new Object[]{ServiceState.STATE_IN_SERVICE});
        tryInvoke(serviceState, "setVoiceRegState", new Class<?>[]{int.class}, new Object[]{ServiceState.STATE_IN_SERVICE});
        tryInvoke(serviceState, "setDataRegState", new Class<?>[]{int.class}, new Object[]{ServiceState.STATE_IN_SERVICE});
        tryInvoke(serviceState, "setRoaming", new Class<?>[]{boolean.class}, new Object[]{false});
        tryInvoke(serviceState, "setOperatorName",
                new Class<?>[]{String.class, String.class, String.class},
                new Object[]{profile.operatorName, profile.operatorName, profile.operatorNumeric()});
        CellIdentityLte lteIdentity = makeLteCellIdentity(profile);
        Object voiceRegistration = makeNetworkRegistrationInfo(
                profile,
                NetworkRegistrationInfo.DOMAIN_CS,
                TelephonyManager.NETWORK_TYPE_LTE,
                Arrays.asList(NetworkRegistrationInfo.SERVICE_TYPE_VOICE, NetworkRegistrationInfo.SERVICE_TYPE_SMS),
                lteIdentity,
                false
        );
        if (voiceRegistration != null) {
            tryInvoke(serviceState, "addNetworkRegistrationInfo",
                    new Class<?>[]{NetworkRegistrationInfo.class},
                    new Object[]{voiceRegistration});
        }
        Object dataIdentity = profile.nrEnabled ? makeNrCellIdentity(profile) : lteIdentity;
        int dataNetworkType = profile.nrEnabled ? TelephonyManager.NETWORK_TYPE_NR : TelephonyManager.NETWORK_TYPE_LTE;
        Object dataRegistration = makeNetworkRegistrationInfo(
                profile,
                NetworkRegistrationInfo.DOMAIN_PS,
                dataNetworkType,
                Arrays.asList(NetworkRegistrationInfo.SERVICE_TYPE_DATA, NetworkRegistrationInfo.SERVICE_TYPE_MMS),
                dataIdentity,
                profile.nrEnabled
        );
        if (dataRegistration != null) {
            tryInvoke(serviceState, "addNetworkRegistrationInfo",
                    new Class<?>[]{NetworkRegistrationInfo.class},
                    new Object[]{dataRegistration});
        }
        return serviceState;
    }

    private static CellIdentityLte makeLteCellIdentity(CellProfile profile) {
        CellInfoLte info = makeLteCellInfo(profile);
        return info == null ? null : info.getCellIdentity();
    }

    private static CellIdentityNr makeNrCellIdentity(CellProfile profile) {
        CellInfoNr info = makeNrCellInfo(profile);
        return info == null ? null : (CellIdentityNr) info.getCellIdentity();
    }

    private static Object makeNetworkRegistrationInfo(
            CellProfile profile,
            int domain,
            int accessNetworkTechnology,
            List<Integer> services,
            Object cellIdentity,
            boolean markNrConnected
    ) {
        Class<?> builderClass = findClass(GhostLocModule.class.getClassLoader(),
                "android.telephony.NetworkRegistrationInfo$Builder");
        if (builderClass == null) {
            return null;
        }
        Object builder = newInstance(builderClass);
        if (builder == null) {
            return null;
        }
        tryInvoke(builder, "setDomain", new Class<?>[]{int.class}, new Object[]{domain});
        tryInvoke(builder, "setTransportType", new Class<?>[]{int.class},
                new Object[]{AccessNetworkConstants.TRANSPORT_TYPE_WWAN});
        tryInvoke(builder, "setRegistrationState", new Class<?>[]{int.class}, new Object[]{1});
        tryInvoke(builder, "setAccessNetworkTechnology", new Class<?>[]{int.class}, new Object[]{accessNetworkTechnology});
        tryInvoke(builder, "setRejectCause", new Class<?>[]{int.class}, new Object[]{0});
        tryInvoke(builder, "setEmergencyOnly", new Class<?>[]{boolean.class}, new Object[]{false});
        tryInvoke(builder, "setAvailableServices", new Class<?>[]{List.class}, new Object[]{services});
        if (cellIdentity instanceof CellIdentity identity) {
            tryInvoke(builder, "setCellIdentity", new Class<?>[]{CellIdentity.class}, new Object[]{identity});
        }
        tryInvoke(builder, "setRegisteredPlmn", new Class<?>[]{String.class}, new Object[]{profile.operatorNumeric()});
        Object registrationInfo = tryInstanceInvoke(builder, "build", new Class<?>[0], new Object[0]);
        if (registrationInfo != null && markNrConnected) {
            tryInvoke(registrationInfo, "setNrState", new Class<?>[]{int.class},
                    new Object[]{NetworkRegistrationInfo.NR_STATE_CONNECTED});
        }
        return registrationInfo;
    }

    private static CarrierPreset carrierPresetFor(double lat, double lon) {
        if (lat >= 29.0 && lat <= 46.5 && lon >= 129.0 && lon <= 146.5) {
            return new CarrierPreset("440", "10", "jp", "NTT DOCOMO", 3, 20_000, 640_000, 78);
        }
        if (lat >= 18.0 && lat <= 54.5 && lon >= 73.0 && lon <= 135.5) {
            return new CarrierPreset("460", "00", "cn", "China Mobile", 41, 20_000, 627_000, 78);
        }
        if (lat >= 32.0 && lat <= 43.0 && lon >= 124.0 && lon <= 132.5) {
            return new CarrierPreset("450", "05", "kr", "SK Telecom", 3, 15_000, 634_000, 78);
        }
        if (lat >= 49.0 && lat <= 61.0 && lon >= -9.0 && lon <= 3.5) {
            return new CarrierPreset("234", "15", "gb", "Vodafone UK", 20, 20_000, 635_000, 78);
        }
        if (lat >= 18.0 && lat <= 72.0 && lon >= -170.0 && lon <= -60.0) {
            return new CarrierPreset("310", "260", "us", "T-Mobile", 2, 20_000, 636_000, 41);
        }
        return new CarrierPreset("001", "01", "us", "GhostLoc", 3, 15_000, 635_000, 78);
    }

    private static String stringPreference(SharedPreferences sharedPreferences, String key, String fallback) {
        String value = sharedPreferences.getString(key, fallback);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static long mixSeed(double lat, double lon, String mcc, String mnc, String operatorName) {
        long seed = Double.doubleToLongBits(lat) * 0x9e3779b97f4a7c15L;
        seed ^= Long.rotateLeft(Double.doubleToLongBits(lon), 29);
        seed ^= ((long) mcc.hashCode() << 32);
        seed ^= mnc.hashCode();
        seed ^= Long.rotateLeft(operatorName.hashCode(), 11);
        return seed ^ 0x51ed270b7f4a7c15L;
    }

    private static int positiveInt(long seed, int bound) {
        return bound <= 0 ? 0 : (int) Math.floorMod(seed, (long) bound);
    }

    private static long positiveLong(long seed, long bound) {
        return bound <= 0 ? 0L : Math.floorMod(seed, bound);
    }

    private static boolean tryInvoke(Object target, String methodName, Class<?>[] types, Object[] args) {
        try {
            Method method = resolveMethod(target.getClass(), methodName, types);
            if (method == null) {
                return false;
            }
            method.setAccessible(true);
            method.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object tryInstanceInvoke(Object target, String methodName, Class<?>[] types, Object[] args) {
        try {
            Method method = resolveMethod(target.getClass(), methodName, types);
            if (method == null) {
                return null;
            }
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryStaticInvoke(Class<?> cls, String methodName, Class<?>[] types, Object[] args) {
        try {
            Method method = resolveMethod(cls, methodName, types);
            if (method == null) {
                return null;
            }
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method resolveMethod(Class<?> cls, String methodName, Class<?>[] types) {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, types);
            } catch (NoSuchMethodException ignored) {
            }
            current = current.getSuperclass();
        }
        try {
            return cls.getMethod(methodName, types);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasMethod(Class<?> cls, String methodName, Class<?>... types) {
        return resolveMethod(cls, methodName, types) != null;
    }

    private static <T> T newInstance(Class<T> cls) {
        try {
            Constructor<T> constructor = cls.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static <T> T newInstance(Class<?> cls, Class<T> type) {
        try {
            Constructor<?> constructor = cls.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object value = constructor.newInstance();
            return type.cast(value);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static boolean setFieldIfPresent(Object target, String fieldName, Object value) {
        Field field = resolveField(target.getClass(), fieldName);
        if (field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field resolveField(Class<?> cls, String fieldName) {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static double[] move(double lat, double lon, float speedMps, float bearingDeg, double seconds) {
        double distance = Math.max(0.0, speedMps) * seconds;
        return moveDistance(lat, lon, bearingDeg, distance);
    }

    private static double[] moveDistance(double lat, double lon, float bearingDeg, double distanceMeters) {
        double distance = Math.max(0.0, distanceMeters);
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

    private static LocationState routeLocationState(
            String provider,
            SharedPreferences preferences,
            List<RoutePoint> route,
            double elapsedSeconds,
            float baseSpeed,
            float speedVariation,
            boolean loop,
            boolean autoBearing,
            float fallbackBearing,
            double baseAltitude,
            float baseAccuracy
    ) {
        double totalDistance = 0.0;
        for (int i = 0; i < route.size() - 1; i++) {
            totalDistance += distanceMeters(route.get(i), route.get(i + 1));
        }
        if (totalDistance <= 0.5) {
            RoutePoint point = route.get(0);
            return new LocationState(
                    provider,
                    point.latitude,
                    point.longitude,
                    variedSpeed(baseSpeed, speedVariation, elapsedSeconds),
                    applyAltitudeJitter(preferences, baseAltitude, elapsedSeconds),
                    applyAccuracyJitter(preferences, baseAccuracy, elapsedSeconds),
                    fallbackBearing);
        }
        double traveled = integratedDistanceMeters(baseSpeed, speedVariation, elapsedSeconds);
        if (loop) {
            traveled %= totalDistance;
        } else {
            traveled = Math.min(traveled, totalDistance);
        }
        for (int i = 0; i < route.size() - 1; i++) {
            RoutePoint start = route.get(i);
            RoutePoint end = route.get(i + 1);
            double segmentDistance = distanceMeters(start, end);
            if (i == route.size() - 2 || traveled <= segmentDistance) {
                double fraction = segmentDistance <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, traveled / segmentDistance));
                double lat = interpolate(start.latitude, end.latitude, fraction);
                double lon = interpolateLongitude(start.longitude, end.longitude, fraction);
                float bearing = autoBearing ? bearingBetween(start, end) : fallbackBearing;
                return new LocationState(
                        provider,
                        lat,
                        lon,
                        variedSpeed(baseSpeed, speedVariation, elapsedSeconds),
                        applyAltitudeJitter(preferences, baseAltitude, elapsedSeconds),
                        applyAccuracyJitter(preferences, baseAccuracy, elapsedSeconds),
                        bearing);
            }
            traveled -= segmentDistance;
        }
        RoutePoint end = route.get(route.size() - 1);
        return new LocationState(
                provider,
                end.latitude,
                end.longitude,
                variedSpeed(baseSpeed, speedVariation, elapsedSeconds),
                applyAltitudeJitter(preferences, baseAltitude, elapsedSeconds),
                applyAccuracyJitter(preferences, baseAccuracy, elapsedSeconds),
                fallbackBearing);
    }

    private static List<RoutePoint> parseRoutePoints(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        ArrayList<RoutePoint> points = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Object value = array.get(i);
                if (value instanceof JSONObject object) {
                    if (object.has("lat") && object.has("lon")) {
                        points.add(new RoutePoint(object.optDouble("lat"), object.optDouble("lon")));
                    }
                } else if (value instanceof JSONArray pair && pair.length() >= 2) {
                    points.add(new RoutePoint(pair.optDouble(0), pair.optDouble(1)));
                }
            }
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
        return points;
    }

    private static double routeElapsedSeconds(SharedPreferences preferences) {
        long elapsedMs = preferences.getBoolean(SettingsStore.ROUTE_PAUSED, false)
                ? preferences.getLong(SettingsStore.ROUTE_PAUSE_ELAPSED_MS, 0L)
                : Math.max(0L, System.currentTimeMillis() - preferences.getLong(SettingsStore.STARTED_AT, 0L));
        return elapsedMs / 1000.0;
    }

    private static float variedSpeed(float baseSpeed, float variation, double elapsedSeconds) {
        float clamped = Math.max(0.0f, Math.min(0.75f, variation));
        double multiplier = 1.0 + clamped * Math.sin(elapsedSeconds / 18.0);
        return (float) Math.max(0.0, baseSpeed * multiplier);
    }

    private static double integratedDistanceMeters(float baseSpeed, float variation, double elapsedSeconds) {
        float clamped = Math.max(0.0f, Math.min(0.75f, variation));
        return Math.max(0.0,
                (baseSpeed * elapsedSeconds)
                        + (baseSpeed * clamped * 18.0 * (1.0 - Math.cos(elapsedSeconds / 18.0))));
    }

    private static float applyAccuracyJitter(SharedPreferences preferences, float baseAccuracy, double elapsedSeconds) {
        float jitter = preferences.getFloat(SettingsStore.ACCURACY_JITTER, SettingsStore.DEFAULT_ACCURACY_JITTER);
        if (jitter <= 0.0f) {
            return baseAccuracy;
        }
        return Math.max(1.0f, (float) (baseAccuracy + Math.sin(elapsedSeconds / 11.0) * jitter));
    }

    private static double applyAltitudeJitter(SharedPreferences preferences, double baseAltitude, double elapsedSeconds) {
        float jitter = preferences.getFloat(SettingsStore.ALTITUDE_JITTER, SettingsStore.DEFAULT_ALTITUDE_JITTER);
        if (jitter <= 0.0f) {
            return baseAltitude;
        }
        return baseAltitude + Math.cos(elapsedSeconds / 13.0) * jitter;
    }

    private static double distanceMeters(RoutePoint start, RoutePoint end) {
        float[] result = new float[1];
        Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, result);
        return result[0];
    }

    private static float bearingBetween(RoutePoint start, RoutePoint end) {
        float[] result = new float[2];
        Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, result);
        return result[1];
    }

    private static double interpolate(double start, double end, double fraction) {
        return start + ((end - start) * fraction);
    }

    private static double interpolateLongitude(double start, double end, double fraction) {
        double delta = normalizeLon(end - start);
        return normalizeLon(start + (delta * fraction));
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
        for (Object arg : args) {
            String provider = providerFromRequest(arg);
            if (provider != null) {
                return provider;
            }
        }
        return LocationManager.GPS_PROVIDER;
    }

    private static String providerFromRequest(Object candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            Method method = candidate.getClass().getMethod("getProvider");
            Object value = method.invoke(candidate);
            if (value instanceof String provider && provider.length() > 0) {
                return provider;
            }
        } catch (Throwable ignored) {
        }
        return null;
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

    private static final class GnssSatelliteProfile {
        final int constellationType;
        final int svid;
        final float cn0DbHz;
        final float elevationDegrees;
        final float azimuthDegrees;
        final float carrierFrequencyHz;
        final boolean usedInFix;

        GnssSatelliteProfile(
                int constellationType,
                int svid,
                float cn0DbHz,
                float elevationDegrees,
                float azimuthDegrees,
                float carrierFrequencyHz,
                boolean usedInFix
        ) {
            this.constellationType = constellationType;
            this.svid = svid;
            this.cn0DbHz = cn0DbHz;
            this.elevationDegrees = elevationDegrees;
            this.azimuthDegrees = azimuthDegrees;
            this.carrierFrequencyHz = carrierFrequencyHz;
            this.usedInFix = usedInFix;
        }
    }

    private static final class RoutePoint {
        final double latitude;
        final double longitude;

        RoutePoint(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final class LocationState {
        final String provider;
        final double latitude;
        final double longitude;
        final float speed;
        final double altitude;
        final float accuracy;
        final float bearing;

        LocationState(
                String provider,
                double latitude,
                double longitude,
                float speed,
                double altitude,
                float accuracy,
                float bearing
        ) {
            this.provider = provider;
            this.latitude = latitude;
            this.longitude = longitude;
            this.speed = speed;
            this.altitude = altitude;
            this.accuracy = accuracy;
            this.bearing = bearing;
        }
    }

    private static final class GnssProfile {
        final GnssSatelliteProfile[] satellites;
        final boolean nmeaEnabled;
        final boolean agpsEnabled;

        GnssProfile(GnssSatelliteProfile[] satellites, boolean nmeaEnabled, boolean agpsEnabled) {
            this.satellites = satellites;
            this.nmeaEnabled = nmeaEnabled;
            this.agpsEnabled = agpsEnabled;
        }
    }

    private static final class CarrierPreset {
        final String mcc;
        final String mnc;
        final String countryIso;
        final String operatorName;
        final int lteBand;
        final int lteBandwidthKhz;
        final int nrArfcnBase;
        final int nrBand;

        CarrierPreset(
                String mcc,
                String mnc,
                String countryIso,
                String operatorName,
                int lteBand,
                int lteBandwidthKhz,
                int nrArfcnBase,
                int nrBand
        ) {
            this.mcc = mcc;
            this.mnc = mnc;
            this.countryIso = countryIso;
            this.operatorName = operatorName;
            this.lteBand = lteBand;
            this.lteBandwidthKhz = lteBandwidthKhz;
            this.nrArfcnBase = nrArfcnBase;
            this.nrBand = nrBand;
        }
    }

    private static final class CellProfile {
        final String mcc;
        final String mnc;
        final String countryIso;
        final String operatorName;
        final boolean nrEnabled;
        final int lteTac;
        final int lteCi;
        final int ltePci;
        final int lteEarfcn;
        final int[] lteBands;
        final int lteBandwidth;
        final int lteRssi;
        final int lteRsrp;
        final int lteRsrq;
        final int lteRssnr;
        final int lteTimingAdvance;
        final int nrTac;
        final long nrNci;
        final int nrPci;
        final int nrArfcn;
        final int[] nrBands;
        final int nrSsRsrp;
        final int nrSsRsrq;
        final int nrSsSinr;
        final int nrCsiRsrp;
        final int nrCsiRsrq;
        final int nrCsiSinr;
        final int nrTimingAdvance;

        CellProfile(
                String mcc,
                String mnc,
                String countryIso,
                String operatorName,
                boolean nrEnabled,
                int lteTac,
                int lteCi,
                int ltePci,
                int lteEarfcn,
                int[] lteBands,
                int lteBandwidth,
                int lteRssi,
                int lteRsrp,
                int lteRsrq,
                int lteRssnr,
                int lteTimingAdvance,
                int nrTac,
                long nrNci,
                int nrPci,
                int nrArfcn,
                int[] nrBands,
                int nrSsRsrp,
                int nrSsRsrq,
                int nrSsSinr,
                int nrCsiRsrp,
                int nrCsiRsrq,
                int nrCsiSinr,
                int nrTimingAdvance
        ) {
            this.mcc = mcc;
            this.mnc = mnc;
            this.countryIso = countryIso;
            this.operatorName = operatorName;
            this.nrEnabled = nrEnabled;
            this.lteTac = lteTac;
            this.lteCi = lteCi;
            this.ltePci = ltePci;
            this.lteEarfcn = lteEarfcn;
            this.lteBands = lteBands;
            this.lteBandwidth = lteBandwidth;
            this.lteRssi = lteRssi;
            this.lteRsrp = lteRsrp;
            this.lteRsrq = lteRsrq;
            this.lteRssnr = lteRssnr;
            this.lteTimingAdvance = lteTimingAdvance;
            this.nrTac = nrTac;
            this.nrNci = nrNci;
            this.nrPci = nrPci;
            this.nrArfcn = nrArfcn;
            this.nrBands = nrBands;
            this.nrSsRsrp = nrSsRsrp;
            this.nrSsRsrq = nrSsRsrq;
            this.nrSsSinr = nrSsSinr;
            this.nrCsiRsrp = nrCsiRsrp;
            this.nrCsiRsrq = nrCsiRsrq;
            this.nrCsiSinr = nrCsiSinr;
            this.nrTimingAdvance = nrTimingAdvance;
        }

        String operatorNumeric() {
            return mcc + mnc;
        }
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

    private static boolean vendorHooksEnabled() {
        return prefs().getBoolean(SettingsStore.VENDOR_HOOKS_ENABLED, true);
    }

    private static SharedPreferences prefs() {
        SharedPreferences current = prefs;
        if (current == null && bridge != null) {
            current = bridge.getRemotePreferences(SettingsStore.GROUP);
            prefs = current;
        }
        return current;
    }

    private static void logHit(XposedInterface.BeforeHookCallback callback, String prefix) {
        Object member = callback.getMember();
        String key = prefix + " " + (member instanceof Method method
                ? method.getDeclaringClass().getName() + "#" + methodSignature(method)
                : String.valueOf(member));
        if (LOGGED_HITS.add(key)) {
            recordHit(key);
            log(key);
        }
    }

    private static void logHit(XposedInterface.AfterHookCallback callback, String prefix) {
        Object member = callback.getMember();
        String key = prefix + " " + (member instanceof Method method
                ? method.getDeclaringClass().getName() + "#" + methodSignature(method)
                : String.valueOf(member));
        if (LOGGED_HITS.add(key)) {
            recordHit(key);
            log(key);
        }
    }

    private static String methodSignature(Method method) {
        StringBuilder builder = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(simpleTypeName(params[i]));
        }
        return builder.append("):").append(simpleTypeName(method.getReturnType())).toString();
    }

    private static String simpleTypeName(Class<?> type) {
        if (type.isArray()) {
            return simpleTypeName(type.getComponentType()) + "[]";
        }
        return type.getName();
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
        recordError(message + " " + throwable.getClass().getSimpleName());
        Log.i(TAG, message, throwable);
        XposedInterface current = bridge;
        if (current != null) {
            try {
                current.log(TAG + ": " + message, throwable);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void recordProcess(String processName) {
        try {
            SharedPreferences current = prefs;
            if (current == null) {
                return;
            }
            current.edit()
                    .putString(SettingsStore.DIAG_LAST_PROCESS, stamp(processName))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static void recordHit(String message) {
        appendDiagnostic(SettingsStore.DIAG_RECENT_HITS, message);
    }

    private static void recordError(String message) {
        appendDiagnostic(SettingsStore.DIAG_RECENT_ERRORS, message);
    }

    private static void appendDiagnostic(String key, String message) {
        try {
            SharedPreferences current = prefs;
            if (current == null) {
                return;
            }
            String existing = current.getString(key, "");
            current.edit()
                    .putString(key, appendLimited(existing, stamp(message), DIAGNOSTIC_LIMIT))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static String appendLimited(String existing, String entry, int maxLines) {
        ArrayList<String> lines = new ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            lines.addAll(Arrays.asList(existing.split("\\n")));
        }
        lines.add(entry);
        while (lines.size() > maxLines) {
            lines.remove(0);
        }
        return String.join("\n", lines);
    }

    private static String stamp(String message) {
        return String.format(Locale.US, "%1$tH:%1$tM:%1$tS %2$s", System.currentTimeMillis(), message);
    }
}
