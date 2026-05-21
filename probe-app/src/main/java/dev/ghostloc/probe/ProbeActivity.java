package dev.ghostloc.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.Criteria;
import android.location.Geocoder;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.TextView;

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
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, new CancellationSignal(),
                        Executors.newSingleThreadExecutor(),
                        current -> Log.i(TAG, String.format(Locale.US,
                                "current_location=%.6f,%.6f mock=%s",
                                current == null ? 0.0 : current.getLatitude(),
                                current == null ? 0.0 : current.getLongitude(),
                                current != null && isMock(current))));
            }
        } catch (Throwable throwable) {
            report.append("location_error=").append(throwable.getClass().getSimpleName()).append("\n");
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
