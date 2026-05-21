package dev.ghostloc.offline;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity implements ModuleApplication.ServiceStateListener {
    private XposedService service;
    private MapView mapView;
    private Marker marker;
    private TextView serviceStatus;
    private TextView runningStatus;
    private EditText latInput;
    private EditText lonInput;
    private EditText speedInput;
    private EditText altitudeInput;
    private EditText accuracyInput;
    private EditText bearingInput;
    private EditText searchInput;
    private CheckBox wifiShield;
    private CheckBox cellShield;
    private CheckBox mockShield;
    private CheckBox movementMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(buildUi());
        requestRuntimePermissions();
        loadSettings();
        updateMarker();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addServiceStateListener(this, true);
    }

    @Override
    protected void onStop() {
        ModuleApplication.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (mapView != null) {
            mapView.onPause();
        }
        super.onPause();
    }

    @Override
    public void onServiceStateChanged(XposedService service) {
        this.service = service;
        runOnUiThread(() -> {
            serviceStatus.setText(service == null ? "Module service: not connected" :
                    "Module service: " + service.getFrameworkName() + " API " + service.getApiVersion());
            loadSettings();
        });
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        serviceStatus = label("Module service: not connected", 13);
        root.addView(serviceStatus);

        LinearLayout searchRow = row();
        searchInput = input("Search place");
        searchRow.addView(searchInput, weight());
        searchRow.addView(button("Search", this::searchPlace), weight());
        root.addView(searchRow);

        mapView = new MapView(this);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);
        mapView.getController().setCenter(new GeoPoint(SettingsStore.DEFAULT_LAT, SettingsStore.DEFAULT_LON));
        root.addView(mapView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        mapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                setCoordinate(p.getLatitude(), p.getLongitude());
                saveSettings(false);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                setCoordinate(p.getLatitude(), p.getLongitude());
                saveSettings(true);
                return true;
            }
        }));

        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(20, 12, 20, 20);
        scroll.addView(panel);

        runningStatus = label("Status: idle", 16);
        panel.addView(runningStatus);

        LinearLayout coordRow = row();
        latInput = input("Latitude");
        lonInput = input("Longitude");
        coordRow.addView(latInput, weight());
        coordRow.addView(lonInput, weight());
        panel.addView(coordRow);

        LinearLayout paramRow = row();
        speedInput = input("Speed m/s");
        altitudeInput = input("Altitude m");
        accuracyInput = input("Accuracy m");
        bearingInput = input("Bearing");
        paramRow.addView(speedInput, weight());
        paramRow.addView(altitudeInput, weight());
        paramRow.addView(accuracyInput, weight());
        paramRow.addView(bearingInput, weight());
        panel.addView(paramRow);

        wifiShield = checkbox("Wi-Fi shield", true);
        cellShield = checkbox("Cell shield", true);
        mockShield = checkbox("Mock shield", true);
        movementMode = checkbox("Move", false);
        LinearLayout shieldRow = row();
        shieldRow.addView(wifiShield, weight());
        shieldRow.addView(cellShield, weight());
        shieldRow.addView(mockShield, weight());
        shieldRow.addView(movementMode, weight());
        panel.addView(shieldRow);

        LinearLayout buttonRow = row();
        buttonRow.addView(button("Start", () -> saveSettings(true)), weight());
        buttonRow.addView(button("Stop", () -> saveSettings(false)), weight());
        buttonRow.addView(button("Save", () -> saveSettings(isActive())), weight());
        panel.addView(buttonRow);

        LinearLayout favRow = row();
        favRow.addView(button("Add Fav", this::addFavorite), weight());
        favRow.addView(button("Use Fav", this::useLastFavorite), weight());
        favRow.addView(button("Clear Fav", this::clearFavorites), weight());
        panel.addView(favRow);

        panel.addView(label("Tap map to select. Long press map to select and start.", 12));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private TextView label(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(25, 25, 25));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(12, 6, 12, 6);
        return view;
    }

    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setTextSize(14);
        view.setHint(hint);
        return view;
    }

    private CheckBox checkbox(String text, boolean checked) {
        CheckBox view = new CheckBox(this);
        view.setText(text);
        view.setChecked(checked);
        return view;
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
    }

    private SharedPreferences prefs() {
        XposedService current = service != null ? service : ModuleApplication.currentService();
        if (current != null) {
            return current.getRemotePreferences(SettingsStore.GROUP);
        }
        return getSharedPreferences(SettingsStore.GROUP, MODE_PRIVATE);
    }

    private void loadSettings() {
        SharedPreferences prefs = prefs();
        boolean active = prefs.getBoolean(SettingsStore.ACTIVE, false);
        double lat = Double.longBitsToDouble(prefs.getLong(SettingsStore.LAT,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LAT)));
        double lon = Double.longBitsToDouble(prefs.getLong(SettingsStore.LON,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LON)));
        float speed = prefs.getFloat(SettingsStore.SPEED, SettingsStore.DEFAULT_SPEED);
        double altitude = Double.longBitsToDouble(prefs.getLong(SettingsStore.ALTITUDE,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_ALTITUDE)));
        float accuracy = prefs.getFloat(SettingsStore.ACCURACY, SettingsStore.DEFAULT_ACCURACY);
        float bearing = prefs.getFloat(SettingsStore.BEARING, SettingsStore.DEFAULT_BEARING);

        latInput.setText(String.format(Locale.US, "%.6f", lat));
        lonInput.setText(String.format(Locale.US, "%.6f", lon));
        speedInput.setText(String.format(Locale.US, "%.2f", speed));
        altitudeInput.setText(String.format(Locale.US, "%.1f", altitude));
        accuracyInput.setText(String.format(Locale.US, "%.1f", accuracy));
        bearingInput.setText(String.format(Locale.US, "%.1f", bearing));
        wifiShield.setChecked(prefs.getBoolean(SettingsStore.WIFI_SHIELD, true));
        cellShield.setChecked(prefs.getBoolean(SettingsStore.CELL_SHIELD, true));
        mockShield.setChecked(prefs.getBoolean(SettingsStore.MOCK_SHIELD, true));
        movementMode.setChecked(prefs.getBoolean(SettingsStore.MOVEMENT, false));
        runningStatus.setText(active ? "Status: spoofing active" : "Status: idle");
    }

    private void saveSettings(boolean active) {
        try {
            double lat = parseDouble(latInput, SettingsStore.DEFAULT_LAT);
            double lon = parseDouble(lonInput, SettingsStore.DEFAULT_LON);
            float speed = (float) parseDouble(speedInput, SettingsStore.DEFAULT_SPEED);
            double altitude = parseDouble(altitudeInput, SettingsStore.DEFAULT_ALTITUDE);
            float accuracy = (float) parseDouble(accuracyInput, SettingsStore.DEFAULT_ACCURACY);
            float bearing = (float) parseDouble(bearingInput, SettingsStore.DEFAULT_BEARING);

            prefs().edit()
                    .putBoolean(SettingsStore.ACTIVE, active)
                    .putLong(SettingsStore.LAT, Double.doubleToRawLongBits(lat))
                    .putLong(SettingsStore.LON, Double.doubleToRawLongBits(lon))
                    .putFloat(SettingsStore.SPEED, speed)
                    .putLong(SettingsStore.ALTITUDE, Double.doubleToRawLongBits(altitude))
                    .putFloat(SettingsStore.ACCURACY, accuracy)
                    .putFloat(SettingsStore.BEARING, bearing)
                    .putBoolean(SettingsStore.WIFI_SHIELD, wifiShield.isChecked())
                    .putBoolean(SettingsStore.CELL_SHIELD, cellShield.isChecked())
                    .putBoolean(SettingsStore.MOCK_SHIELD, mockShield.isChecked())
                    .putBoolean(SettingsStore.MOVEMENT, movementMode.isChecked())
                    .putLong(SettingsStore.STARTED_AT, active ? System.currentTimeMillis() : 0L)
                    .putString(SettingsStore.LAST_STATUS, active ? "started" : "stopped")
                    .apply();
            runningStatus.setText(active ? "Status: spoofing active" : "Status: idle");
            updateMarker();
            Toast.makeText(this, active ? "Started" : "Saved", Toast.LENGTH_SHORT).show();
        } catch (Throwable throwable) {
            Toast.makeText(this, "Invalid configuration", Toast.LENGTH_SHORT).show();
        }
    }

    private void searchPlace() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Enter a place", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Searching...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                URL url = new URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + encoded);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", getPackageName() + "/0.1");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                byte[] bytes = connection.getInputStream().readAllBytes();
                JSONArray results = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                if (results.length() == 0) {
                    runOnUiThread(() -> Toast.makeText(this, "No result", Toast.LENGTH_SHORT).show());
                    return;
                }
                JSONObject first = results.getJSONObject(0);
                double lat = Double.parseDouble(first.getString("lat"));
                double lon = Double.parseDouble(first.getString("lon"));
                runOnUiThread(() -> {
                    setCoordinate(lat, lon);
                    saveSettings(isActive());
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> Toast.makeText(this, "Search failed", Toast.LENGTH_SHORT).show());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "ghostloc-search").start();
    }

    private void addFavorite() {
        double lat = parseDouble(latInput, SettingsStore.DEFAULT_LAT);
        double lon = parseDouble(lonInput, SettingsStore.DEFAULT_LON);
        String existing = prefs().getString(SettingsStore.FAVORITES, "");
        String item = String.format(Locale.US, "%.6f,%.6f", lat, lon);
        String updated = existing == null || existing.isEmpty() ? item : existing + ";" + item;
        prefs().edit().putString(SettingsStore.FAVORITES, updated).apply();
        Toast.makeText(this, "Favorite saved", Toast.LENGTH_SHORT).show();
    }

    private void useLastFavorite() {
        String favorites = prefs().getString(SettingsStore.FAVORITES, "");
        if (favorites == null || favorites.isEmpty()) {
            Toast.makeText(this, "No favorite", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = favorites.split(";");
        String[] parts = items[items.length - 1].split(",");
        if (parts.length != 2) {
            Toast.makeText(this, "Favorite invalid", Toast.LENGTH_SHORT).show();
            return;
        }
        setCoordinate(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        saveSettings(isActive());
    }

    private void clearFavorites() {
        prefs().edit().remove(SettingsStore.FAVORITES).apply();
        Toast.makeText(this, "Favorites cleared", Toast.LENGTH_SHORT).show();
    }

    private boolean isActive() {
        return prefs().getBoolean(SettingsStore.ACTIVE, false);
    }

    private double parseDouble(EditText input, double fallback) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) {
            return fallback;
        }
        return Double.parseDouble(value);
    }

    private void setCoordinate(double lat, double lon) {
        latInput.setText(String.format(Locale.US, "%.6f", lat));
        lonInput.setText(String.format(Locale.US, "%.6f", lon));
        updateMarker();
    }

    private void updateMarker() {
        if (mapView == null || latInput == null || lonInput == null) {
            return;
        }
        double lat = parseDouble(latInput, SettingsStore.DEFAULT_LAT);
        double lon = parseDouble(lonInput, SettingsStore.DEFAULT_LON);
        GeoPoint point = new GeoPoint(lat, lon);
        if (marker != null) {
            mapView.getOverlays().remove(marker);
        }
        marker = new Marker(mapView);
        marker.setPosition(point);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle(String.format(Locale.US, "%.6f, %.6f", lat, lon));
        mapView.getOverlays().add(marker);
        mapView.getController().animateTo(point);
        mapView.invalidate();
    }

    private void requestRuntimePermissions() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, 42);
        }
    }
}
