package dev.ghostloc.offline;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity implements ModuleApplication.ServiceStateListener {
    public static final String EXTRA_AUTO_APPLY = "dev.ghostloc.offline.extra.AUTO_APPLY";
    public static final String EXTRA_ACTIVE = "dev.ghostloc.offline.extra.ACTIVE";
    public static final String EXTRA_LAT = "dev.ghostloc.offline.extra.LAT";
    public static final String EXTRA_LON = "dev.ghostloc.offline.extra.LON";
    public static final String EXTRA_MOVEMENT = "dev.ghostloc.offline.extra.MOVEMENT";
    public static final String EXTRA_ROUTE_JSON = "dev.ghostloc.offline.extra.ROUTE_JSON";
    public static final String EXTRA_ROUTE_JSON_B64 = "dev.ghostloc.offline.extra.ROUTE_JSON_B64";
    public static final String EXTRA_FAVORITES_JSON = "dev.ghostloc.offline.extra.FAVORITES_JSON";
    public static final String EXTRA_FAVORITES_JSON_B64 = "dev.ghostloc.offline.extra.FAVORITES_JSON_B64";
    public static final String EXTRA_ROUTE_LOOP = "dev.ghostloc.offline.extra.ROUTE_LOOP";
    public static final String EXTRA_ROUTE_AUTO_BEARING = "dev.ghostloc.offline.extra.ROUTE_AUTO_BEARING";
    public static final String EXTRA_ROUTE_SPEED_VARIATION = "dev.ghostloc.offline.extra.ROUTE_SPEED_VARIATION";
    public static final String EXTRA_NMEA_ENABLED = "dev.ghostloc.offline.extra.NMEA_ENABLED";
    public static final String EXTRA_VENDOR_HOOKS = "dev.ghostloc.offline.extra.VENDOR_HOOKS";
    public static final String EXTRA_NR_ENABLED = "dev.ghostloc.offline.extra.NR_ENABLED";
    public static final String EXTRA_FINISH = "dev.ghostloc.offline.extra.FINISH";

    private static final String[] RESET_KEYS = {
            SettingsStore.ACTIVE,
            SettingsStore.LAT,
            SettingsStore.LON,
            SettingsStore.SPEED,
            SettingsStore.ALTITUDE,
            SettingsStore.ACCURACY,
            SettingsStore.BEARING,
            SettingsStore.WIFI_SHIELD,
            SettingsStore.CELL_SHIELD,
            SettingsStore.MOCK_SHIELD,
            SettingsStore.CELL_MCC,
            SettingsStore.CELL_MNC,
            SettingsStore.CELL_COUNTRY_ISO,
            SettingsStore.CELL_OPERATOR_NAME,
            SettingsStore.CELL_ENABLE_NR,
            SettingsStore.GNSS_SAT_COUNT,
            SettingsStore.NMEA_ENABLED,
            SettingsStore.AGPS_ENABLED,
            SettingsStore.VENDOR_HOOKS_ENABLED,
            SettingsStore.MOVEMENT,
            SettingsStore.STARTED_AT,
            SettingsStore.FAVORITES,
            SettingsStore.ROUTE_POINTS,
            SettingsStore.ROUTE_LOOP,
            SettingsStore.ROUTE_AUTO_BEARING,
            SettingsStore.ROUTE_PAUSED,
            SettingsStore.ROUTE_PAUSE_ELAPSED_MS,
            SettingsStore.ROUTE_SPEED_VARIATION,
            SettingsStore.ACCURACY_JITTER,
            SettingsStore.ALTITUDE_JITTER,
            SettingsStore.LAST_PROBE_REPORT,
            SettingsStore.LAST_PROBE_OK,
            SettingsStore.LAST_PROBE_AT,
            SettingsStore.LAST_STATUS
    };

    private static final OnlineTileSourceBase GAODE_ROAD_TILE_SOURCE = new OnlineTileSourceBase(
            "UsbWorldTileRelayV3",
            3,
            19,
            256,
            ".png",
            new String[]{
                    "http://127.0.0.1:18765/world-tile-v3?x="
            },
            "USB Relay",
            new TileSourcePolicy(2, 0)
    ) {
        @Override
        public String getTileURLString(long mapTileIndex) {
            return getBaseUrl()
                    + MapTileIndex.getX(mapTileIndex)
                    + "&y=" + MapTileIndex.getY(mapTileIndex)
                    + "&z=" + MapTileIndex.getZoom(mapTileIndex);
        }
    };

    private XposedService service;
    private MapView mapView;
    private Marker marker;
    private TextView serviceStatus;
    private TextView runningStatus;
    private TextView diagnosticsStatus;
    private EditText latInput;
    private EditText lonInput;
    private EditText speedInput;
    private EditText altitudeInput;
    private EditText accuracyInput;
    private EditText bearingInput;
    private EditText searchInput;
    private EditText mccInput;
    private EditText mncInput;
    private EditText countryInput;
    private EditText operatorInput;
    private EditText gnssSatInput;
    private EditText routeSpeedVariationInput;
    private EditText accuracyJitterInput;
    private EditText altitudeJitterInput;
    private EditText routeJsonInput;
    private EditText favoritesJsonInput;
    private CheckBox wifiShield;
    private CheckBox cellShield;
    private CheckBox mockShield;
    private CheckBox movementMode;
    private CheckBox nrEnabled;
    private CheckBox nmeaEnabled;
    private CheckBox agpsEnabled;
    private CheckBox vendorHooksEnabled;
    private CheckBox routeLoop;
    private CheckBox routeAutoBearing;
    private boolean pendingAutoApply;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(getCacheDir());
        Configuration.getInstance().setOsmdroidTileCache(getCacheDir());
        setContentView(buildUi());
        requestRuntimePermissions();
        loadSettings();
        pendingAutoApply = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO_APPLY, false);
        if (pendingAutoApply) {
            applyIntentSettings(false);
        }
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
        refreshDiagnostics();
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
            serviceStatus.setText(service == null ? "模块服务：未连接"
                    : "模块服务：" + service.getFrameworkName() + " API " + service.getApiVersion());
            loadSettings();
            if (pendingAutoApply && service != null) {
                applyIntentSettings(true);
            }
        });
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        serviceStatus = label("模块服务：未连接", 13, true);
        root.addView(serviceStatus);

        LinearLayout searchRow = row();
        searchInput = input("搜索地点", false);
        searchRow.addView(searchInput, weight());
        searchRow.addView(button("搜索", this::searchPlace), weight());
        root.addView(searchRow);
        root.addView(label("地图源：USB 本机代理（全球底图）", 12, false));

        mapView = new MapView(this);
        mapView.setTileSource(GAODE_ROAD_TILE_SOURCE);
        mapView.setMultiTouchControls(true);
        mapView.setUseDataConnection(true);
        mapView.setTilesScaledToDpi(true);
        mapView.getController().setZoom(14.0);
        mapView.getController().setCenter(new GeoPoint(SettingsStore.DEFAULT_LAT, SettingsStore.DEFAULT_LON));
        root.addView(mapView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260)));

        mapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                setCoordinate(p.getLatitude(), p.getLongitude());
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                setCoordinate(p.getLatitude(), p.getLongitude());
                startSpoofing();
                return true;
            }
        }));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(20, 12, 20, 28);
        scrollView.addView(panel);

        runningStatus = label("状态：空闲", 16, true);
        panel.addView(runningStatus);
        panel.addView(label("单击地图仅放点，长按地图会放点并立即开始。", 12, false));

        panel.addView(sectionTitle("位置"));
        LinearLayout coordRow = row();
        latInput = input("纬度", false);
        lonInput = input("经度", false);
        coordRow.addView(latInput, weight());
        coordRow.addView(lonInput, weight());
        panel.addView(coordRow);

        LinearLayout baseRow = row();
        speedInput = input("速度 m/s", false);
        altitudeInput = input("海拔 m", false);
        accuracyInput = input("精度 m", false);
        bearingInput = input("方向角", false);
        baseRow.addView(speedInput, weight());
        baseRow.addView(altitudeInput, weight());
        baseRow.addView(accuracyInput, weight());
        baseRow.addView(bearingInput, weight());
        panel.addView(baseRow);

        panel.addView(sectionTitle("策略"));
        wifiShield = checkbox("Wi-Fi 屏蔽", true);
        cellShield = checkbox("基站屏蔽", true);
        mockShield = checkbox("Mock 标记屏蔽", true);
        movementMode = checkbox("路线移动", false);
        LinearLayout policyRow1 = row();
        policyRow1.addView(wifiShield, weight());
        policyRow1.addView(cellShield, weight());
        policyRow1.addView(mockShield, weight());
        policyRow1.addView(movementMode, weight());
        panel.addView(policyRow1);

        nrEnabled = checkbox("启用 5G/NR", true);
        nmeaEnabled = checkbox("启用 NMEA 回调", true);
        agpsEnabled = checkbox("上报 AGPS", true);
        vendorHooksEnabled = checkbox("启用厂商/Fused Hook", true);
        LinearLayout policyRow2 = row();
        policyRow2.addView(nrEnabled, weight());
        policyRow2.addView(nmeaEnabled, weight());
        policyRow2.addView(agpsEnabled, weight());
        policyRow2.addView(vendorHooksEnabled, weight());
        panel.addView(policyRow2);

        panel.addView(sectionTitle("蜂窝与抖动"));
        LinearLayout carrierRow = row();
        mccInput = input("MCC", false);
        mncInput = input("MNC", false);
        countryInput = input("国家/地区", false);
        operatorInput = input("运营商", false);
        carrierRow.addView(mccInput, weight());
        carrierRow.addView(mncInput, weight());
        carrierRow.addView(countryInput, weight());
        carrierRow.addView(operatorInput, weight());
        panel.addView(carrierRow);

        LinearLayout tuningRow = row();
        gnssSatInput = input("GNSS 星数", false);
        routeSpeedVariationInput = input("速度波动 0-0.75", false);
        accuracyJitterInput = input("精度抖动 m", false);
        altitudeJitterInput = input("海拔抖动 m", false);
        tuningRow.addView(gnssSatInput, weight());
        tuningRow.addView(routeSpeedVariationInput, weight());
        tuningRow.addView(accuracyJitterInput, weight());
        tuningRow.addView(altitudeJitterInput, weight());
        panel.addView(tuningRow);

        panel.addView(sectionTitle("路线"));
        routeLoop = checkbox("循环路线", false);
        routeAutoBearing = checkbox("自动方向角", true);
        LinearLayout routeToggleRow = row();
        routeToggleRow.addView(routeLoop, weight());
        routeToggleRow.addView(routeAutoBearing, weight());
        panel.addView(routeToggleRow);
        routeJsonInput = input("路线 JSON", true);
        panel.addView(routeJsonInput);
        LinearLayout routeButtonRow1 = row();
        routeButtonRow1.addView(button("添加点位", this::addRoutePoint), weight());
        routeButtonRow1.addView(button("保存路线", this::saveRouteFromEditor), weight());
        routeButtonRow1.addView(button("使用起点", this::useRouteStart), weight());
        routeButtonRow1.addView(button("清空路线", this::clearRoute), weight());
        panel.addView(routeButtonRow1);
        LinearLayout routeButtonRow2 = row();
        routeButtonRow2.addView(button("开始", this::startSpoofing), weight());
        routeButtonRow2.addView(button("暂停", this::pauseRoute), weight());
        routeButtonRow2.addView(button("继续", this::resumeRoute), weight());
        routeButtonRow2.addView(button("停止", this::stopSpoofing), weight());
        panel.addView(routeButtonRow2);

        panel.addView(sectionTitle("收藏"));
        favoritesJsonInput = input("收藏 JSON", true);
        panel.addView(favoritesJsonInput);
        LinearLayout favoriteRow1 = row();
        favoriteRow1.addView(button("加入当前点", this::addFavorite), weight());
        favoriteRow1.addView(button("保存 JSON", this::saveFavoritesFromEditor), weight());
        favoriteRow1.addView(button("使用最近项", this::useLastFavorite), weight());
        favoriteRow1.addView(button("清空收藏", this::clearFavorites), weight());
        panel.addView(favoriteRow1);
        LinearLayout favoriteRow2 = row();
        favoriteRow2.addView(button("保存配置", this::saveCurrentState), weight());
        favoriteRow2.addView(button("应用并验证", this::applyAndLaunchProbe), weight());
        favoriteRow2.addView(button("仅启动验证", this::launchProbe), weight());
        favoriteRow2.addView(button("恢复默认", this::resetDefaults), weight());
        panel.addView(favoriteRow2);

        panel.addView(sectionTitle("诊断"));
        diagnosticsStatus = label("", 12, false);
        diagnosticsStatus.setTextIsSelectable(true);
        panel.addView(diagnosticsStatus);
        LinearLayout diagRow = row();
        diagRow.addView(button("刷新", this::refreshDiagnostics), weight());
        diagRow.addView(button("重载", this::loadSettings), weight());
        panel.addView(diagRow);

        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        return root;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private TextView sectionTitle(String title) {
        TextView view = label(title, 14, true);
        view.setPadding(12, 18, 12, 6);
        return view;
    }

    private TextView label(String text, int sp, boolean boldish) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(20, 20, 20));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(12, 6, 12, 6);
        if (boldish) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private EditText input(String hint, boolean multiline) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setTextSize(13);
        view.setTextColor(Color.rgb(20, 20, 20));
        if (multiline) {
            view.setSingleLine(false);
            view.setMinLines(4);
            view.setGravity(Gravity.TOP | Gravity.START);
            view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        } else {
            view.setSingleLine(true);
        }
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
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        params.setMargins(4, 4, 4, 4);
        return params;
    }

    private SharedPreferences prefs() {
        XposedService current = service != null ? service : ModuleApplication.currentService();
        if (current != null) {
            return current.getRemotePreferences(SettingsStore.GROUP);
        }
        return getSharedPreferences(SettingsStore.GROUP, MODE_PRIVATE);
    }

    private SharedPreferences localPrefs() {
        return getSharedPreferences(SettingsStore.GROUP, MODE_PRIVATE);
    }

    private void loadSettings() {
        SharedPreferences prefs = prefs();
        boolean active = prefs.getBoolean(SettingsStore.ACTIVE, false);
        double lat = Double.longBitsToDouble(prefs.getLong(SettingsStore.LAT,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LAT)));
        double lon = Double.longBitsToDouble(prefs.getLong(SettingsStore.LON,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_LON)));
        latInput.setText(formatDouble(lat, 6));
        lonInput.setText(formatDouble(lon, 6));
        speedInput.setText(formatDouble(prefs.getFloat(SettingsStore.SPEED, SettingsStore.DEFAULT_SPEED), 2));
        altitudeInput.setText(formatDouble(Double.longBitsToDouble(prefs.getLong(SettingsStore.ALTITUDE,
                Double.doubleToRawLongBits(SettingsStore.DEFAULT_ALTITUDE))), 1));
        accuracyInput.setText(formatDouble(prefs.getFloat(SettingsStore.ACCURACY, SettingsStore.DEFAULT_ACCURACY), 1));
        bearingInput.setText(formatDouble(prefs.getFloat(SettingsStore.BEARING, SettingsStore.DEFAULT_BEARING), 1));
        mccInput.setText(prefs.getString(SettingsStore.CELL_MCC, ""));
        mncInput.setText(prefs.getString(SettingsStore.CELL_MNC, ""));
        countryInput.setText(prefs.getString(SettingsStore.CELL_COUNTRY_ISO, ""));
        operatorInput.setText(prefs.getString(SettingsStore.CELL_OPERATOR_NAME, ""));
        gnssSatInput.setText(String.valueOf(prefs.getInt(SettingsStore.GNSS_SAT_COUNT, SettingsStore.DEFAULT_GNSS_SAT_COUNT)));
        routeSpeedVariationInput.setText(formatDouble(
                prefs.getFloat(SettingsStore.ROUTE_SPEED_VARIATION, SettingsStore.DEFAULT_ROUTE_SPEED_VARIATION), 2));
        accuracyJitterInput.setText(formatDouble(
                prefs.getFloat(SettingsStore.ACCURACY_JITTER, SettingsStore.DEFAULT_ACCURACY_JITTER), 1));
        altitudeJitterInput.setText(formatDouble(
                prefs.getFloat(SettingsStore.ALTITUDE_JITTER, SettingsStore.DEFAULT_ALTITUDE_JITTER), 1));
        wifiShield.setChecked(prefs.getBoolean(SettingsStore.WIFI_SHIELD, true));
        cellShield.setChecked(prefs.getBoolean(SettingsStore.CELL_SHIELD, true));
        mockShield.setChecked(prefs.getBoolean(SettingsStore.MOCK_SHIELD, true));
        movementMode.setChecked(prefs.getBoolean(SettingsStore.MOVEMENT, false));
        nrEnabled.setChecked(prefs.getBoolean(SettingsStore.CELL_ENABLE_NR, true));
        nmeaEnabled.setChecked(prefs.getBoolean(SettingsStore.NMEA_ENABLED, true));
        agpsEnabled.setChecked(prefs.getBoolean(SettingsStore.AGPS_ENABLED, true));
        vendorHooksEnabled.setChecked(prefs.getBoolean(SettingsStore.VENDOR_HOOKS_ENABLED, true));
        routeLoop.setChecked(prefs.getBoolean(SettingsStore.ROUTE_LOOP, false));
        routeAutoBearing.setChecked(prefs.getBoolean(SettingsStore.ROUTE_AUTO_BEARING, true));
        routeJsonInput.setText(routeJsonForDisplay(prefs.getString(SettingsStore.ROUTE_POINTS, "")));
        favoritesJsonInput.setText(favoritesJsonForDisplay(prefs.getString(SettingsStore.FAVORITES, "")));
        updateRunningStatus(prefs);
        updateMarker();
        refreshDiagnostics();
    }

    private void saveCurrentState() {
        commitSettings(isActive(), false, "已保存配置");
    }

    private void startSpoofing() {
        commitSettings(true, true, "Started");
    }

    private void stopSpoofing() {
        commitSettings(false, true, "Stopped");
    }

    private void commitSettings(boolean active, boolean resetTimer, String toastText) {
        try {
            SharedPreferences current = prefs();
            boolean wasActive = current.getBoolean(SettingsStore.ACTIVE, false);
            long startedAt = current.getLong(SettingsStore.STARTED_AT, 0L);
            boolean routePausedValue = current.getBoolean(SettingsStore.ROUTE_PAUSED, false);
            long pauseElapsed = current.getLong(SettingsStore.ROUTE_PAUSE_ELAPSED_MS, 0L);
            if (!active) {
                startedAt = 0L;
                routePausedValue = false;
                pauseElapsed = 0L;
            } else if (resetTimer || !wasActive || startedAt == 0L) {
                startedAt = System.currentTimeMillis();
                routePausedValue = false;
                pauseElapsed = 0L;
            }

            double lat = parseDouble(latInput, SettingsStore.DEFAULT_LAT);
            double lon = parseDouble(lonInput, SettingsStore.DEFAULT_LON);
            float speed = (float) parseDouble(speedInput, SettingsStore.DEFAULT_SPEED);
            double altitude = parseDouble(altitudeInput, SettingsStore.DEFAULT_ALTITUDE);
            float accuracy = (float) parseDouble(accuracyInput, SettingsStore.DEFAULT_ACCURACY);
            float bearing = (float) parseDouble(bearingInput, SettingsStore.DEFAULT_BEARING);
            int gnssSatellites = parseInt(gnssSatInput, SettingsStore.DEFAULT_GNSS_SAT_COUNT);
            float routeVariation = clampFloat((float) parseDouble(routeSpeedVariationInput,
                    SettingsStore.DEFAULT_ROUTE_SPEED_VARIATION), 0.0f, 0.75f);
            float accuracyJitter = Math.max(0.0f, (float) parseDouble(accuracyJitterInput,
                    SettingsStore.DEFAULT_ACCURACY_JITTER));
            float altitudeJitter = Math.max(0.0f, (float) parseDouble(altitudeJitterInput,
                    SettingsStore.DEFAULT_ALTITUDE_JITTER));
            String routeJson = normalizeRouteJson(routeJsonInput.getText().toString());
            String favoritesJson = normalizeFavoritesJson(favoritesJsonInput.getText().toString());

            SharedPreferences.Editor editor = current.edit();
            editor.putBoolean(SettingsStore.ACTIVE, active)
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
                    .putString(SettingsStore.CELL_MCC, textOf(mccInput))
                    .putString(SettingsStore.CELL_MNC, textOf(mncInput))
                    .putString(SettingsStore.CELL_COUNTRY_ISO, textOf(countryInput))
                    .putString(SettingsStore.CELL_OPERATOR_NAME, textOf(operatorInput))
                    .putBoolean(SettingsStore.CELL_ENABLE_NR, nrEnabled.isChecked())
                    .putInt(SettingsStore.GNSS_SAT_COUNT, gnssSatellites)
                    .putBoolean(SettingsStore.NMEA_ENABLED, nmeaEnabled.isChecked())
                    .putBoolean(SettingsStore.AGPS_ENABLED, agpsEnabled.isChecked())
                    .putBoolean(SettingsStore.VENDOR_HOOKS_ENABLED, vendorHooksEnabled.isChecked())
                    .putString(SettingsStore.ROUTE_POINTS, routeJson)
                    .putBoolean(SettingsStore.ROUTE_LOOP, routeLoop.isChecked())
                    .putBoolean(SettingsStore.ROUTE_AUTO_BEARING, routeAutoBearing.isChecked())
                    .putBoolean(SettingsStore.ROUTE_PAUSED, routePausedValue)
                    .putLong(SettingsStore.ROUTE_PAUSE_ELAPSED_MS, pauseElapsed)
                    .putFloat(SettingsStore.ROUTE_SPEED_VARIATION, routeVariation)
                    .putFloat(SettingsStore.ACCURACY_JITTER, accuracyJitter)
                    .putFloat(SettingsStore.ALTITUDE_JITTER, altitudeJitter)
                    .putString(SettingsStore.FAVORITES, favoritesJson)
                    .putLong(SettingsStore.STARTED_AT, startedAt)
                    .putString(SettingsStore.LAST_STATUS, active
                            ? (routePausedValue ? "route-paused" : "started")
                            : "stopped")
                    .apply();
            sendBridgeCommand(active ? "start" : "stop", lat, lon, speed, altitude, accuracy, bearing);
            routeJsonInput.setText(routeJsonForDisplay(routeJson));
            favoritesJsonInput.setText(favoritesJsonForDisplay(favoritesJson));
            updateRunningStatus(prefs());
            updateMarker();
            refreshDiagnostics();
            if (toastText != null && !toastText.isEmpty()) {
                Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable throwable) {
            Toast.makeText(this, "Invalid configuration", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendBridgeCommand(
            String command,
            double lat,
            double lon,
            float speed,
            double altitude,
            float accuracy,
            float bearing
    ) {
        try {
            Bundle bundle = new Bundle();
            bundle.putDouble("lat", lat);
            bundle.putDouble("lon", lon);
            bundle.putDouble("speed", speed);
            bundle.putDouble("altitude", altitude);
            bundle.putDouble("accuracy", accuracy);
            bundle.putDouble("bearing", bearing);
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            boolean handled = manager != null && manager.sendExtraCommand("ghostloc", command, bundle);
            android.util.Log.i("GhostLocApp", "bridge command=" + command + " handled=" + handled);
        } catch (Throwable throwable) {
            android.util.Log.i("GhostLocApp", "bridge command failed=" + command + " "
                    + throwable.getClass().getSimpleName());
        }
    }

    private void applyIntentSettings(boolean remoteReady) {
        if (getIntent() == null) {
            return;
        }
        Bundle extras = getIntent().getExtras();
        double lat = readDoubleExtra(EXTRA_LAT, SettingsStore.DEFAULT_LAT);
        double lon = readDoubleExtra(EXTRA_LON, SettingsStore.DEFAULT_LON);
        boolean active = getIntent().getBooleanExtra(EXTRA_ACTIVE, true);
        setCoordinate(lat, lon);
        if (extras != null) {
            if (extras.containsKey(EXTRA_MOVEMENT)) {
                movementMode.setChecked(extras.getBoolean(EXTRA_MOVEMENT, movementMode.isChecked()));
            }
            if (extras.containsKey(EXTRA_ROUTE_LOOP)) {
                routeLoop.setChecked(extras.getBoolean(EXTRA_ROUTE_LOOP, routeLoop.isChecked()));
            }
            if (extras.containsKey(EXTRA_ROUTE_AUTO_BEARING)) {
                routeAutoBearing.setChecked(extras.getBoolean(EXTRA_ROUTE_AUTO_BEARING, routeAutoBearing.isChecked()));
            }
            if (extras.containsKey(EXTRA_NMEA_ENABLED)) {
                nmeaEnabled.setChecked(extras.getBoolean(EXTRA_NMEA_ENABLED, nmeaEnabled.isChecked()));
            }
            if (extras.containsKey(EXTRA_VENDOR_HOOKS)) {
                vendorHooksEnabled.setChecked(extras.getBoolean(EXTRA_VENDOR_HOOKS, vendorHooksEnabled.isChecked()));
            }
            if (extras.containsKey(EXTRA_NR_ENABLED)) {
                nrEnabled.setChecked(extras.getBoolean(EXTRA_NR_ENABLED, nrEnabled.isChecked()));
            }
        }
        String routeJson = decodeBase64Extra(EXTRA_ROUTE_JSON_B64);
        if (routeJson == null) {
            routeJson = readStringExtra(EXTRA_ROUTE_JSON);
        }
        if (routeJson != null) {
            routeJsonInput.setText(routeJsonForDisplay(routeJson));
        }
        String favoritesJson = decodeBase64Extra(EXTRA_FAVORITES_JSON_B64);
        if (favoritesJson == null) {
            favoritesJson = readStringExtra(EXTRA_FAVORITES_JSON);
        }
        if (favoritesJson != null) {
            favoritesJsonInput.setText(favoritesJsonForDisplay(favoritesJson));
        }
        Double routeVariation = readOptionalDoubleExtra(EXTRA_ROUTE_SPEED_VARIATION);
        if (routeVariation != null) {
            routeSpeedVariationInput.setText(formatDouble(routeVariation, 2));
        }
        commitSettings(active, true, null);
        if (remoteReady) {
            pendingAutoApply = false;
            if (getIntent().getBooleanExtra(EXTRA_FINISH, false)) {
                finish();
            }
        }
    }

    private double readDoubleExtra(String key, double fallback) {
        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        Object value = extras == null ? null : extras.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private Double readOptionalDoubleExtra(String key) {
        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        if (extras == null || !extras.containsKey(key)) {
            return null;
        }
        Object value = extras.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String readStringExtra(String key) {
        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        Object value = extras == null ? null : extras.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String decodeBase64Extra(String key) {
        String value = readStringExtra(key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void searchPlace() {
        String query = textOf(searchInput);
        if (query.isEmpty()) {
            Toast.makeText(this, "请输入地点关键字", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在搜索...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                if (!Geocoder.isPresent()) {
                    runOnUiThread(() -> Toast.makeText(this, "当前系统未提供地点搜索服务，请直接点地图选点", Toast.LENGTH_SHORT).show());
                    return;
                }
                Geocoder geocoder = new Geocoder(this, Locale.SIMPLIFIED_CHINESE);
                List<Address> results = geocoder.getFromLocationName(query, 1);
                if (results == null || results.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "未找到地点，请直接点地图或手填坐标", Toast.LENGTH_SHORT).show());
                    return;
                }
                Address first = results.get(0);
                double lat = first.getLatitude();
                double lon = first.getLongitude();
                runOnUiThread(() -> {
                    setCoordinate(lat, lon);
                    saveCurrentState();
                    Toast.makeText(this, "已定位到搜索结果", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> Toast.makeText(this, "搜索失败，请直接点地图或手填坐标", Toast.LENGTH_SHORT).show());
            }
        }, "ghostloc-search").start();
    }

    private void addFavorite() {
        try {
            List<FavoriteEntry> favorites = parseFavorites(normalizeFavoritesJson(textOf(favoritesJsonInput)));
            favorites.add(new FavoriteEntry(
                    "收藏点 " + (favorites.size() + 1),
                    textOf(countryInput).isEmpty() ? "默认" : textOf(countryInput),
                    parseDouble(latInput, SettingsStore.DEFAULT_LAT),
                    parseDouble(lonInput, SettingsStore.DEFAULT_LON)));
            saveFavorites(favorites, "已保存收藏点");
        } catch (Throwable throwable) {
            Toast.makeText(this, "当前收藏内容无效", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveFavoritesFromEditor() {
        try {
            saveFavorites(parseFavorites(normalizeFavoritesJson(textOf(favoritesJsonInput))), "已保存收藏列表");
        } catch (Throwable throwable) {
            Toast.makeText(this, "收藏 JSON 格式无效", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveFavorites(List<FavoriteEntry> favorites, String toastText) {
        String json = favoritesToJson(favorites);
        prefs().edit().putString(SettingsStore.FAVORITES, json).apply();
        favoritesJsonInput.setText(favoritesJsonForDisplay(json));
        refreshDiagnostics();
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show();
    }

    private void useLastFavorite() {
        List<FavoriteEntry> favorites = parseFavorites(prefs().getString(SettingsStore.FAVORITES, ""));
        if (favorites.isEmpty()) {
            Toast.makeText(this, "暂无收藏点", Toast.LENGTH_SHORT).show();
            return;
        }
        FavoriteEntry entry = favorites.get(favorites.size() - 1);
        setCoordinate(entry.latitude, entry.longitude);
        saveCurrentState();
    }

    private void clearFavorites() {
        prefs().edit().remove(SettingsStore.FAVORITES).apply();
        favoritesJsonInput.setText("[]");
        refreshDiagnostics();
        Toast.makeText(this, "已清空收藏", Toast.LENGTH_SHORT).show();
    }

    private void addRoutePoint() {
        try {
            List<RouteEntry> route = parseRoute(normalizeRouteJson(textOf(routeJsonInput)));
            route.add(new RouteEntry(
                    "点位 " + (route.size() + 1),
                    parseDouble(latInput, SettingsStore.DEFAULT_LAT),
                    parseDouble(lonInput, SettingsStore.DEFAULT_LON)));
            saveRoute(route, "已保存路线点");
        } catch (Throwable throwable) {
            Toast.makeText(this, "当前路线内容无效", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveRouteFromEditor() {
        try {
            saveRoute(parseRoute(normalizeRouteJson(textOf(routeJsonInput))), "已保存路线");
        } catch (Throwable throwable) {
            Toast.makeText(this, "路线 JSON 格式无效", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveRoute(List<RouteEntry> route, String toastText) {
        String json = routeToJson(route);
        prefs().edit().putString(SettingsStore.ROUTE_POINTS, json).apply();
        routeJsonInput.setText(routeJsonForDisplay(json));
        refreshDiagnostics();
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show();
    }

    private void useRouteStart() {
        List<RouteEntry> route = parseRoute(prefs().getString(SettingsStore.ROUTE_POINTS, ""));
        if (route.isEmpty()) {
            Toast.makeText(this, "当前没有路线", Toast.LENGTH_SHORT).show();
            return;
        }
        RouteEntry entry = route.get(0);
        setCoordinate(entry.latitude, entry.longitude);
        saveCurrentState();
    }

    private void clearRoute() {
        SharedPreferences.Editor editor = prefs().edit();
        editor.remove(SettingsStore.ROUTE_POINTS)
                .putBoolean(SettingsStore.ROUTE_PAUSED, false)
                .putLong(SettingsStore.ROUTE_PAUSE_ELAPSED_MS, 0L)
                .apply();
        routeJsonInput.setText("[]");
        refreshDiagnostics();
        Toast.makeText(this, "已清空路线", Toast.LENGTH_SHORT).show();
    }

    private void pauseRoute() {
        SharedPreferences current = prefs();
        if (!current.getBoolean(SettingsStore.ACTIVE, false) || !movementMode.isChecked()) {
            Toast.makeText(this, "请先启动路线移动", Toast.LENGTH_SHORT).show();
            return;
        }
        long startedAt = current.getLong(SettingsStore.STARTED_AT, 0L);
        long elapsed = startedAt == 0L ? 0L : Math.max(0L, System.currentTimeMillis() - startedAt);
        current.edit()
                .putBoolean(SettingsStore.ROUTE_PAUSED, true)
                .putLong(SettingsStore.ROUTE_PAUSE_ELAPSED_MS, elapsed)
                .putString(SettingsStore.LAST_STATUS, "route-paused")
                .apply();
        updateRunningStatus(prefs());
        refreshDiagnostics();
        Toast.makeText(this, "路线已暂停", Toast.LENGTH_SHORT).show();
    }

    private void resumeRoute() {
        SharedPreferences current = prefs();
        if (!current.getBoolean(SettingsStore.ACTIVE, false)) {
            Toast.makeText(this, "当前未启动定位伪装", Toast.LENGTH_SHORT).show();
            return;
        }
        long pausedElapsed = current.getLong(SettingsStore.ROUTE_PAUSE_ELAPSED_MS, 0L);
        current.edit()
                .putBoolean(SettingsStore.ROUTE_PAUSED, false)
                .putLong(SettingsStore.STARTED_AT, Math.max(0L, System.currentTimeMillis() - pausedElapsed))
                .putString(SettingsStore.LAST_STATUS, "route-resumed")
                .apply();
        updateRunningStatus(prefs());
        refreshDiagnostics();
        Toast.makeText(this, "路线已继续", Toast.LENGTH_SHORT).show();
    }

    private void applyAndLaunchProbe() {
        startSpoofing();
        launchProbe();
    }

    private void launchProbe() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("dev.ghostloc.probe");
        if (launch == null) {
            Toast.makeText(this, "验证应用未安装", Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void resetDefaults() {
        SharedPreferences.Editor editor = prefs().edit();
        for (String key : RESET_KEYS) {
            editor.remove(key);
        }
        editor.putString(SettingsStore.LAST_STATUS, "reset").apply();
        SharedPreferences.Editor localEditor = localPrefs().edit();
        localEditor.remove(SettingsStore.LAST_PROBE_REPORT)
                .remove(SettingsStore.LAST_PROBE_OK)
                .remove(SettingsStore.LAST_PROBE_AT)
                .apply();
        loadSettings();
        Toast.makeText(this, "已恢复默认配置", Toast.LENGTH_SHORT).show();
    }

    private boolean isActive() {
        return prefs().getBoolean(SettingsStore.ACTIVE, false);
    }

    private void refreshDiagnostics() {
        SharedPreferences prefs = prefs();
        SharedPreferences localPrefs = localPrefs();
        int favoriteCount = 0;
        int routeCount = 0;
        try {
            favoriteCount = parseFavorites(prefs.getString(SettingsStore.FAVORITES, "")).size();
        } catch (Throwable ignored) {
        }
        try {
            routeCount = parseRoute(prefs.getString(SettingsStore.ROUTE_POINTS, "")).size();
        } catch (Throwable ignored) {
        }
        String lastProcess = prefs.getString(SettingsStore.DIAG_LAST_PROCESS, "n/a");
        String hits = prefs.getString(SettingsStore.DIAG_RECENT_HITS, "none");
        String errors = prefs.getString(SettingsStore.DIAG_RECENT_ERRORS, "none");
        String probeReport = localPrefs.getString(SettingsStore.LAST_PROBE_REPORT, "无");
        boolean probeOk = localPrefs.getBoolean(SettingsStore.LAST_PROBE_OK, false);
        long probeAt = localPrefs.getLong(SettingsStore.LAST_PROBE_AT, 0L);
        String text = "框架服务：" + (service == null ? "未连接" : service.getFrameworkName() + " API " + service.getApiVersion())
                + "\n地图源：USB 本机代理（全球底图）"
                + "\n已启用：" + prefs.getBoolean(SettingsStore.ACTIVE, false)
                + "  路线暂停：" + prefs.getBoolean(SettingsStore.ROUTE_PAUSED, false)
                + "  最近状态：" + describeLastStatus(prefs.getString(SettingsStore.LAST_STATUS, "idle"))
                + "\n最近进程：" + lastProcess
                + "\n收藏数量：" + favoriteCount
                + "  路线点数：" + routeCount
                + "  厂商 Hook：" + prefs.getBoolean(SettingsStore.VENDOR_HOOKS_ENABLED, true)
                + "\n最近验证：" + (probeAt == 0L ? "无" : (probeOk ? "通过 " : "失败 ") + stamp(probeAt))
                + "\n建议作用域：system_server、android、com.android.location.fused、dev.ghostloc.probe、dev.ghostloc.demo"
                + "\n可选作用域：com.oplus.location、com.xiaomi.location.fused"
                + "\n验证报告：\n" + probeReport
                + "\n最近命中：\n" + hits
                + "\n最近错误：\n" + errors;
        diagnosticsStatus.setText(text);
    }

    private double parseDouble(EditText input, double fallback) {
        String value = textOf(input);
        if (value.isEmpty()) {
            return fallback;
        }
        return Double.parseDouble(value);
    }

    private int parseInt(EditText input, int fallback) {
        String value = textOf(input);
        if (value.isEmpty()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private String textOf(EditText input) {
        return input.getText().toString().trim();
    }

    private void setCoordinate(double lat, double lon) {
        latInput.setText(formatDouble(lat, 6));
        lonInput.setText(formatDouble(lon, 6));
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
        mapView.post(() -> {
            mapView.getController().setCenter(point);
            mapView.invalidate();
        });
    }

    private void updateRunningStatus(SharedPreferences prefs) {
        boolean active = prefs.getBoolean(SettingsStore.ACTIVE, false);
        boolean paused = prefs.getBoolean(SettingsStore.ROUTE_PAUSED, false);
        String status;
        if (!active) {
            status = "状态：空闲";
        } else if (paused) {
            status = "状态：定位伪装已启用（路线已暂停）";
        } else if (prefs.getBoolean(SettingsStore.MOVEMENT, false)) {
            status = "状态：定位伪装已启用（路线移动中）";
        } else {
            status = "状态：定位伪装已启用";
        }
        runningStatus.setText(status);
    }

    private String describeLastStatus(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "空闲";
        }
        switch (raw) {
            case "idle":
                return "空闲";
            case "route-paused":
                return "路线已暂停";
            case "route-resumed":
                return "路线已继续";
            case "reset":
                return "已恢复默认";
            case "started":
                return "已启动";
            case "stopped":
                return "已停止";
            default:
                return raw;
        }
    }

    private String formatDouble(double value, int digits) {
        return String.format(Locale.US, "%." + digits + "f", value);
    }

    private String stamp(long millis) {
        return String.format(Locale.US, "%1$tH:%1$tM:%1$tS", millis);
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private String favoritesJsonForDisplay(String raw) {
        try {
            return new JSONArray(normalizeFavoritesJson(raw)).toString(2);
        } catch (Throwable ignored) {
            return "[]";
        }
    }

    private String routeJsonForDisplay(String raw) {
        try {
            return new JSONArray(normalizeRouteJson(raw)).toString(2);
        } catch (Throwable ignored) {
            return "[]";
        }
    }

    private String normalizeFavoritesJson(String raw) {
        List<FavoriteEntry> favorites = parseFavorites(raw);
        return favoritesToJson(favorites);
    }

    private String normalizeRouteJson(String raw) {
        List<RouteEntry> route = parseRoute(raw);
        return routeToJson(route);
    }

    private List<FavoriteEntry> parseFavorites(String raw) {
        ArrayList<FavoriteEntry> favorites = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return favorites;
        }
        if (!raw.trim().startsWith("[")) {
            String[] items = raw.split(";");
            for (int i = 0; i < items.length; i++) {
                String[] parts = items[i].split(",");
                if (parts.length >= 2) {
                    favorites.add(new FavoriteEntry(
                            "收藏点 " + (i + 1),
                            "历史",
                            Double.parseDouble(parts[0].trim()),
                            Double.parseDouble(parts[1].trim())));
                }
            }
            return favorites;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                favorites.add(new FavoriteEntry(
                        item.optString("name", "收藏点 " + (i + 1)),
                        item.optString("group", "默认"),
                        item.getDouble("lat"),
                        item.getDouble("lon")));
            }
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("favorites json", throwable);
        }
        return favorites;
    }

    private String favoritesToJson(List<FavoriteEntry> favorites) {
        JSONArray array = new JSONArray();
        try {
            for (FavoriteEntry favorite : favorites) {
                JSONObject object = new JSONObject();
                object.put("name", favorite.name);
                object.put("group", favorite.group);
                object.put("lat", favorite.latitude);
                object.put("lon", favorite.longitude);
                array.put(object);
            }
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("favorites serialize", throwable);
        }
        return array.toString();
    }

    private List<RouteEntry> parseRoute(String raw) {
        ArrayList<RouteEntry> route = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return route;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                route.add(new RouteEntry(
                        item.optString("name", "点位 " + (i + 1)),
                        item.getDouble("lat"),
                        item.getDouble("lon")));
            }
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("route json", throwable);
        }
        return route;
    }

    private String routeToJson(List<RouteEntry> route) {
        JSONArray array = new JSONArray();
        try {
            for (RouteEntry entry : route) {
                JSONObject object = new JSONObject();
                object.put("name", entry.name);
                object.put("lat", entry.latitude);
                object.put("lon", entry.longitude);
                array.put(object);
            }
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("route serialize", throwable);
        }
        return array.toString();
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

    private static final class FavoriteEntry {
        final String name;
        final String group;
        final double latitude;
        final double longitude;

        FavoriteEntry(String name, String group, double latitude, double longitude) {
            this.name = name;
            this.group = group;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final class RouteEntry {
        final String name;
        final double latitude;
        final double longitude;

        RouteEntry(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
