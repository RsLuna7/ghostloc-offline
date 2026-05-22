package dev.ghostloc.demo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Log;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "GhostLocDemo";
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        output = new TextView(this);
        output.setTextSize(14);
        output.setPadding(24, 24, 24, 24);
        output.setText("Running ordinary location demo...");
        setContentView(output);
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, 9);
            return;
        }
        runDemo();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        runDemo();
    }

    private void runDemo() {
        StringBuilder report = new StringBuilder();
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) {
                report.append("manager=null");
                output.setText(report.toString());
                return;
            }
            report.append("providers=").append(manager.getAllProviders())
                    .append(" best=").append(manager.getBestProvider(new Criteria(), true))
                    .append('\n');
            Location gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location fused = null;
            try {
                fused = manager.getLastKnownLocation(LocationManager.FUSED_PROVIDER);
            } catch (Throwable ignored) {
            }
            report.append("gps_last=").append(formatLocation(gps)).append('\n');
            report.append("fused_last=").append(formatLocation(fused)).append('\n');
            Log.i(TAG, "gps_last=" + formatLocation(gps));
            Log.i(TAG, "fused_last=" + formatLocation(fused));
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, new CancellationSignal(),
                        Executors.newSingleThreadExecutor(),
                        value -> Log.i(TAG, "gps_current=" + formatLocation(value)));
                manager.getCurrentLocation(LocationManager.FUSED_PROVIDER, new CancellationSignal(),
                        Executors.newSingleThreadExecutor(),
                        value -> Log.i(TAG, "fused_current=" + formatLocation(value)));
            }
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    Log.i(TAG, "gps_listener=" + formatLocation(location));
                    try {
                        manager.removeUpdates(this);
                    } catch (Throwable throwable) {
                        Log.i(TAG, "remove_error=" + throwable.getClass().getSimpleName());
                    }
                }
            };
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener);
        } catch (Throwable throwable) {
            report.append("error=").append(throwable.getClass().getSimpleName());
            Log.i(TAG, "demo_error=" + throwable.getClass().getSimpleName());
        }
        output.setText(report.toString());
    }

    private String formatLocation(Location location) {
        if (location == null) {
            return "null";
        }
        return String.format(Locale.US, "%.6f,%.6f speed=%.2f alt=%.1f acc=%.1f provider=%s mock=%s",
                location.getLatitude(),
                location.getLongitude(),
                location.getSpeed(),
                location.getAltitude(),
                location.getAccuracy(),
                location.getProvider(),
                isMock(location));
    }

    private boolean isMock(Location location) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                return location.isMock();
            }
            return location.isFromMockProvider();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
