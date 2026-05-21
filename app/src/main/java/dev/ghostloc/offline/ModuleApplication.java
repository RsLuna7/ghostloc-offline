package dev.ghostloc.offline;

import android.app.Application;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ModuleApplication extends Application implements XposedServiceHelper.OnServiceListener {
    private static final String TAG = "GhostLocApp";

    public interface ServiceStateListener {
        void onServiceStateChanged(XposedService service);
    }

    private static final CopyOnWriteArraySet<ServiceStateListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile XposedService service;

    public static void addServiceStateListener(ServiceStateListener listener, boolean notifyImmediately) {
        LISTENERS.add(listener);
        if (notifyImmediately) {
            listener.onServiceStateChanged(service);
        }
    }

    public static void removeServiceStateListener(ServiceStateListener listener) {
        LISTENERS.remove(listener);
    }

    static XposedService currentService() {
        return service;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService service) {
        ModuleApplication.service = service;
        Log.i(TAG, "service bound framework=" + service.getFrameworkName() + " api=" + service.getApiVersion());
        dispatch(service);
    }

    @Override
    public void onServiceDied(XposedService service) {
        ModuleApplication.service = null;
        Log.i(TAG, "service died");
        dispatch(null);
    }

    private static void dispatch(XposedService service) {
        for (ServiceStateListener listener : LISTENERS) {
            listener.onServiceStateChanged(service);
        }
    }
}

