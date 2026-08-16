package dev.ghostloc.offline

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import dev.ghostloc.offline.core.Prefs
import dev.ghostloc.offline.databinding.ActivityMainBinding
import dev.ghostloc.offline.ui.ConfigDraft
import dev.ghostloc.offline.ui.DiagnosticsFragment
import dev.ghostloc.offline.ui.MainActivityCallback
import dev.ghostloc.offline.ui.MapFragment
import dev.ghostloc.offline.ui.ParamsFragment
import dev.ghostloc.offline.ui.RouteFavoritesFragment
import dev.ghostloc.offline.ui.UiActions
import dev.ghostloc.offline.ui.JsonModels
import io.github.libxposed.service.XposedService
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), ModuleApplication.ServiceStateListener, UiActions, MainActivityCallback {

    companion object {
        const val EXTRA_AUTO_APPLY = "dev.ghostloc.offline.extra.AUTO_APPLY"
        const val EXTRA_ACTIVE = "dev.ghostloc.offline.extra.ACTIVE"
        const val EXTRA_LAT = "dev.ghostloc.offline.extra.LAT"
        const val EXTRA_LON = "dev.ghostloc.offline.extra.LON"
        const val EXTRA_MOVEMENT = "dev.ghostloc.offline.extra.MOVEMENT"
        const val EXTRA_ROUTE_JSON = "dev.ghostloc.offline.extra.ROUTE_JSON"
        const val EXTRA_ROUTE_JSON_B64 = "dev.ghostloc.offline.extra.ROUTE_JSON_B64"
        const val EXTRA_FAVORITES_JSON = "dev.ghostloc.offline.extra.FAVORITES_JSON"
        const val EXTRA_FAVORITES_JSON_B64 = "dev.ghostloc.offline.extra.FAVORITES_JSON_B64"
        const val EXTRA_ROUTE_LOOP = "dev.ghostloc.offline.extra.ROUTE_LOOP"
        const val EXTRA_ROUTE_AUTO_BEARING = "dev.ghostloc.offline.extra.ROUTE_AUTO_BEARING"
        const val EXTRA_ROUTE_SPEED_VARIATION = "dev.ghostloc.offline.extra.ROUTE_SPEED_VARIATION"
        const val EXTRA_NMEA_ENABLED = "dev.ghostloc.offline.extra.NMEA_ENABLED"
        const val EXTRA_VENDOR_HOOKS = "dev.ghostloc.offline.extra.VENDOR_HOOKS"
        const val EXTRA_NR_ENABLED = "dev.ghostloc.offline.extra.NR_ENABLED"
        const val EXTRA_FINISH = "dev.ghostloc.offline.extra.FINISH"

        private val RESET_KEYS = arrayOf(
            Prefs.ACTIVE, Prefs.LAT, Prefs.LON, Prefs.SPEED, Prefs.ALTITUDE, Prefs.ACCURACY, Prefs.BEARING,
            Prefs.WIFI_SHIELD, Prefs.CELL_SHIELD, Prefs.MOCK_SHIELD, Prefs.CELL_MCC, Prefs.CELL_MNC,
            Prefs.CELL_COUNTRY_ISO, Prefs.CELL_OPERATOR_NAME, Prefs.CELL_ENABLE_NR, Prefs.GNSS_SAT_COUNT,
            Prefs.NMEA_ENABLED, Prefs.AGPS_ENABLED, Prefs.VENDOR_HOOKS_ENABLED, Prefs.MOVEMENT, Prefs.STARTED_AT,
            Prefs.FAVORITES, Prefs.ROUTE_POINTS, Prefs.ROUTE_LOOP, Prefs.ROUTE_AUTO_BEARING, Prefs.ROUTE_PAUSED,
            Prefs.ROUTE_PAUSE_ELAPSED_MS, Prefs.ROUTE_SPEED_VARIATION, Prefs.ACCURACY_JITTER,
            Prefs.ALTITUDE_JITTER, Prefs.LAST_PROBE_REPORT, Prefs.LAST_PROBE_OK, Prefs.LAST_PROBE_AT,
            Prefs.LAST_STATUS,
        )
    }

    private lateinit var binding: ActivityMainBinding
    private var service: XposedService? = null
    private var pendingAutoApply = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.pager.adapter = TabsAdapter(this)
        binding.pager.offscreenPageLimit = 3
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_map)
                1 -> getString(R.string.tab_params)
                2 -> getString(R.string.tab_route)
                else -> getString(R.string.tab_diagnostics)
            }
        }.attach()

        requestRuntimePermissions()
        loadSettings()
        if (intent?.getBooleanExtra(EXTRA_AUTO_APPLY, false) == true && service != null) {
            applyIntentSettings(true)
        } else if (intent?.getBooleanExtra(EXTRA_AUTO_APPLY, false) == true) {
            pendingAutoApply = true
        }
    }

    override fun onStart() {
        super.onStart()
        ModuleApplication.addServiceStateListener(this, true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_APPLY, false)) {
            applyIntentSettings(service != null)
        }
    }

    override fun onStop() {
        ModuleApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(newService: XposedService?) {
        service = newService
        runOnUiThread {
            binding.serviceStatus.text = if (newService == null) {
                getString(R.string.service_not_connected)
            } else {
                "模块服务：" + newService.frameworkName + " API " + newService.apiVersion
            }
            loadSettings()
            if (pendingAutoApply && newService != null) {
                applyIntentSettings(true)
            }
        }
    }

    // ---- UiActions ----

    /**
     * On this framework (Vector/LSPosed) the hook layer reads the module
     * app's own `shared_prefs/settings.xml` directly, while the libxposed
     * service remote-prefs API persists to a separate database the hooks
     * never see. Writing anything other than this local file would split
     * the UI and hook state apart, so both reads and writes go through it.
     */
    override fun prefs(): SharedPreferences = localPrefs()

    override fun localPrefs(): SharedPreferences {
        return getSharedPreferences(Prefs.GROUP, Context.MODE_PRIVATE)
    }

    override fun isActive(): Boolean = prefs().getBoolean(Prefs.ACTIVE, false)

    override fun commit(active: Boolean, resetTimer: Boolean, toast: String?) {
        try {
            val current = prefs()
            var wasActive = current.getBoolean(Prefs.ACTIVE, false)
            var startedAt = current.getLong(Prefs.STARTED_AT, 0L)
            var routePaused = current.getBoolean(Prefs.ROUTE_PAUSED, false)
            var pauseElapsed = current.getLong(Prefs.ROUTE_PAUSE_ELAPSED_MS, 0L)
            if (!active) {
                startedAt = 0L
                routePaused = false
                pauseElapsed = 0L
            } else if (resetTimer || !wasActive || startedAt == 0L) {
                startedAt = System.currentTimeMillis()
                routePaused = false
                pauseElapsed = 0L
            }

            val lat = ConfigDraft.latOr()
            val lon = ConfigDraft.lonOr()
            val speed = ConfigDraft.floatOf(ConfigDraft.speedText, Prefs.DEFAULT_SPEED)
            val altitude = ConfigDraft.doubleOf(ConfigDraft.altitudeText, Prefs.DEFAULT_ALTITUDE)
            val accuracy = ConfigDraft.floatOf(ConfigDraft.accuracyText, Prefs.DEFAULT_ACCURACY)
            val bearing = ConfigDraft.floatOf(ConfigDraft.bearingText, Prefs.DEFAULT_BEARING)
            val gnssSatellites = ConfigDraft.intOf(ConfigDraft.gnssSatText, Prefs.DEFAULT_GNSS_SAT_COUNT)
            val routeVariation = min(0.75f, max(0.0f, ConfigDraft.floatOf(ConfigDraft.speedVariationText, Prefs.DEFAULT_ROUTE_SPEED_VARIATION)))
            val accuracyJitter = max(0.0f, ConfigDraft.floatOf(ConfigDraft.accuracyJitterText, Prefs.DEFAULT_ACCURACY_JITTER))
            val altitudeJitter = max(0.0f, ConfigDraft.floatOf(ConfigDraft.altitudeJitterText, Prefs.DEFAULT_ALTITUDE_JITTER))
            val routeJson = JsonModels.normalizeRoute(ConfigDraft.routeJsonText)
            val favoritesJson = JsonModels.normalizeFavorites(ConfigDraft.favoritesJsonText)

            current.edit()
                .putBoolean(Prefs.ACTIVE, active)
                .putLong(Prefs.LAT, lat.toRawBits())
                .putLong(Prefs.LON, lon.toRawBits())
                .putFloat(Prefs.SPEED, speed)
                .putLong(Prefs.ALTITUDE, altitude.toRawBits())
                .putFloat(Prefs.ACCURACY, accuracy)
                .putFloat(Prefs.BEARING, bearing)
                .putBoolean(Prefs.WIFI_SHIELD, ConfigDraft.wifiShield)
                .putBoolean(Prefs.CELL_SHIELD, ConfigDraft.cellShield)
                .putBoolean(Prefs.MOCK_SHIELD, ConfigDraft.mockShield)
                .putBoolean(Prefs.MOVEMENT, ConfigDraft.movement)
                .putString(Prefs.CELL_MCC, ConfigDraft.mccText)
                .putString(Prefs.CELL_MNC, ConfigDraft.mncText)
                .putString(Prefs.CELL_COUNTRY_ISO, ConfigDraft.countryText)
                .putString(Prefs.CELL_OPERATOR_NAME, ConfigDraft.operatorText)
                .putBoolean(Prefs.CELL_ENABLE_NR, ConfigDraft.nrEnabled)
                .putInt(Prefs.GNSS_SAT_COUNT, gnssSatellites)
                .putBoolean(Prefs.NMEA_ENABLED, ConfigDraft.nmeaEnabled)
                .putBoolean(Prefs.AGPS_ENABLED, ConfigDraft.agpsEnabled)
                .putBoolean(Prefs.VENDOR_HOOKS_ENABLED, ConfigDraft.vendorHooksEnabled)
                .putString(Prefs.ROUTE_POINTS, routeJson)
                .putBoolean(Prefs.ROUTE_LOOP, ConfigDraft.routeLoop)
                .putBoolean(Prefs.ROUTE_AUTO_BEARING, ConfigDraft.routeAutoBearing)
                .putBoolean(Prefs.ROUTE_PAUSED, routePaused)
                .putLong(Prefs.ROUTE_PAUSE_ELAPSED_MS, pauseElapsed)
                .putFloat(Prefs.ROUTE_SPEED_VARIATION, routeVariation)
                .putFloat(Prefs.ACCURACY_JITTER, accuracyJitter)
                .putFloat(Prefs.ALTITUDE_JITTER, altitudeJitter)
                .putString(Prefs.FAVORITES, favoritesJson)
                .putLong(Prefs.STARTED_AT, startedAt)
                .putString(Prefs.LAST_STATUS, if (active) (if (routePaused) "route-paused" else "started") else "stopped")
                .apply()

            sendSyncCommand(
                active = active, startedAt = startedAt, lat = lat, lon = lon,
                speed = speed, altitude = altitude, accuracy = accuracy, bearing = bearing,
                routeJson = routeJson, favoritesJson = favoritesJson,
                routePaused = routePaused, pauseElapsed = pauseElapsed,
            )
            ConfigDraft.routeJsonText = JsonModels.routeForDisplay(routeJson)
            ConfigDraft.favoritesJsonText = JsonModels.favoritesForDisplay(favoritesJson)
            refreshAll()
            if (!toast.isNullOrEmpty()) {
                Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.toast_invalid_config, Toast.LENGTH_SHORT).show()
        }
    }

    override fun pauseRoute() {
        val current = prefs()
        if (!current.getBoolean(Prefs.ACTIVE, false) || !ConfigDraft.movement) {
            Toast.makeText(this, R.string.toast_route_need_start, Toast.LENGTH_SHORT).show()
            return
        }
        val startedAt = current.getLong(Prefs.STARTED_AT, 0L)
        val elapsed = if (startedAt == 0L) 0L else max(0L, System.currentTimeMillis() - startedAt)
        current.edit()
            .putBoolean(Prefs.ROUTE_PAUSED, true)
            .putLong(Prefs.ROUTE_PAUSE_ELAPSED_MS, elapsed)
            .putString(Prefs.LAST_STATUS, "route-paused")
            .apply()
        refreshAll()
        Toast.makeText(this, R.string.toast_route_paused, Toast.LENGTH_SHORT).show()
    }

    override fun resumeRoute() {
        val current = prefs()
        if (!current.getBoolean(Prefs.ACTIVE, false)) {
            Toast.makeText(this, R.string.toast_not_active, Toast.LENGTH_SHORT).show()
            return
        }
        val pausedElapsed = current.getLong(Prefs.ROUTE_PAUSE_ELAPSED_MS, 0L)
        current.edit()
            .putBoolean(Prefs.ROUTE_PAUSED, false)
            .putLong(Prefs.STARTED_AT, max(0L, System.currentTimeMillis() - pausedElapsed))
            .putString(Prefs.LAST_STATUS, "route-resumed")
            .apply()
        refreshAll()
        Toast.makeText(this, R.string.toast_route_resumed, Toast.LENGTH_SHORT).show()
    }

    override fun launchProbe() {
        val launch = packageManager.getLaunchIntentForPackage("dev.ghostloc.probe")
        if (launch == null) {
            Toast.makeText(this, R.string.toast_probe_missing, Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    override fun refreshAll() {
        updateRunningStatus()
        forEachFragment { fragment ->
            when (fragment) {
                is MapFragment -> fragment.onDraftChanged(this)
                is ParamsFragment -> fragment.syncFromDraft()
                is RouteFavoritesFragment -> fragment.syncFromDraft()
                is DiagnosticsFragment -> fragment.refreshDiagnostics()
            }
        }
    }

    // ---- MainActivityCallback ----

    override fun reloadFromPrefs() {
        loadSettings()
    }

    override fun resetDefaults() {
        val editor = prefs().edit()
        for (key in RESET_KEYS) {
            editor.remove(key)
        }
        editor.putString(Prefs.LAST_STATUS, "reset").apply()
        localPrefs().edit()
            .remove(Prefs.LAST_PROBE_REPORT)
            .remove(Prefs.LAST_PROBE_OK)
            .remove(Prefs.LAST_PROBE_AT)
            .apply()
        loadSettings()
        Toast.makeText(this, R.string.toast_reset_done, Toast.LENGTH_SHORT).show()
    }

    override fun frameworkDescription(): String {
        val current = service ?: return "未连接"
        return current.frameworkName + " API " + current.apiVersion
    }

    // ---- internals ----

    /**
     * targetSdk 35 enforces edge-to-edge: the window extends behind the
     * status bar, cutout, and gesture bar, so the root must pad itself from
     * the reported insets or the top text and bottom buttons sit under the
     * system bars. Keyboard insets are included so bottom controls rise above
     * the IME.
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            android.util.Log.i("GhostLocApp", "insets applied t=${bars.top} b=${bars.bottom} l=${bars.left} r=${bars.right}")
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun loadSettings() {
        val prefs = prefs()
        val local = localPrefs()
        ConfigDraft.apply {
            latText = formatDouble(Double.fromBits(prefs.getLong(Prefs.LAT, Prefs.DEFAULT_LAT.toRawBits())), 6)
            lonText = formatDouble(Double.fromBits(prefs.getLong(Prefs.LON, Prefs.DEFAULT_LON.toRawBits())), 6)
            speedText = formatDouble(prefs.getFloat(Prefs.SPEED, Prefs.DEFAULT_SPEED), 2)
            altitudeText = formatDouble(Double.fromBits(prefs.getLong(Prefs.ALTITUDE, Prefs.DEFAULT_ALTITUDE.toRawBits())), 1)
            accuracyText = formatDouble(prefs.getFloat(Prefs.ACCURACY, Prefs.DEFAULT_ACCURACY), 1)
            bearingText = formatDouble(prefs.getFloat(Prefs.BEARING, Prefs.DEFAULT_BEARING), 1)
            mccText = prefs.getString(Prefs.CELL_MCC, "").orEmpty()
            mncText = prefs.getString(Prefs.CELL_MNC, "").orEmpty()
            countryText = prefs.getString(Prefs.CELL_COUNTRY_ISO, "").orEmpty()
            operatorText = prefs.getString(Prefs.CELL_OPERATOR_NAME, "").orEmpty()
            gnssSatText = prefs.getInt(Prefs.GNSS_SAT_COUNT, Prefs.DEFAULT_GNSS_SAT_COUNT).toString()
            speedVariationText = formatDouble(prefs.getFloat(Prefs.ROUTE_SPEED_VARIATION, Prefs.DEFAULT_ROUTE_SPEED_VARIATION), 2)
            accuracyJitterText = formatDouble(prefs.getFloat(Prefs.ACCURACY_JITTER, Prefs.DEFAULT_ACCURACY_JITTER), 1)
            altitudeJitterText = formatDouble(prefs.getFloat(Prefs.ALTITUDE_JITTER, Prefs.DEFAULT_ALTITUDE_JITTER), 1)
            wifiShield = prefs.getBoolean(Prefs.WIFI_SHIELD, true)
            cellShield = prefs.getBoolean(Prefs.CELL_SHIELD, true)
            mockShield = prefs.getBoolean(Prefs.MOCK_SHIELD, true)
            movement = prefs.getBoolean(Prefs.MOVEMENT, false)
            nrEnabled = prefs.getBoolean(Prefs.CELL_ENABLE_NR, true)
            nmeaEnabled = prefs.getBoolean(Prefs.NMEA_ENABLED, true)
            agpsEnabled = prefs.getBoolean(Prefs.AGPS_ENABLED, true)
            vendorHooksEnabled = prefs.getBoolean(Prefs.VENDOR_HOOKS_ENABLED, true)
            routeLoop = prefs.getBoolean(Prefs.ROUTE_LOOP, false)
            routeAutoBearing = prefs.getBoolean(Prefs.ROUTE_AUTO_BEARING, true)
            routeJsonText = JsonModels.routeForDisplay(prefs.getString(Prefs.ROUTE_POINTS, ""))
            favoritesJsonText = JsonModels.favoritesForDisplay(prefs.getString(Prefs.FAVORITES, ""))
            tileSourceId = local.getString(Prefs.UI_TILE_SOURCE, Prefs.TILE_SOURCE_AMAP_ROAD) ?: Prefs.TILE_SOURCE_AMAP_ROAD
            pickupGcj02Correct = local.getBoolean(Prefs.UI_PICKUP_GCJ02_CORRECT, true)
        }
        refreshAll()
    }

    private fun updateRunningStatus() {
        val prefs = prefs()
        val active = prefs.getBoolean(Prefs.ACTIVE, false)
        val paused = prefs.getBoolean(Prefs.ROUTE_PAUSED, false)
        binding.runningStatus.text = when {
            !active -> getString(R.string.status_idle)
            paused -> getString(R.string.status_active_paused)
            prefs.getBoolean(Prefs.MOVEMENT, false) -> getString(R.string.status_active_moving)
            else -> getString(R.string.status_active)
        }
    }

    /**
     * Pushes the complete configuration through the bridge command channel.
     * Invoked via reflection: the public SDK signature of sendExtraCommand
     * returns void on most API levels, so calling it directly would couple us
     * to one platform jar. The hook layer only sees writes that arrive through
     * this channel, so it must carry every setting, not just the coordinates.
     */
    private fun sendSyncCommand(
        active: Boolean,
        startedAt: Long,
        lat: Double,
        lon: Double,
        speed: Float,
        altitude: Double,
        accuracy: Float,
        bearing: Float,
        routeJson: String,
        favoritesJson: String,
        routePaused: Boolean,
        pauseElapsed: Long,
    ) {
        try {
            val bundle = Bundle().apply {
                putBoolean("active", active)
                putLong("started_at", startedAt)
                putDouble("lat", lat)
                putDouble("lon", lon)
                putDouble("speed", speed.toDouble())
                putDouble("altitude", altitude)
                putDouble("accuracy", accuracy.toDouble())
                putDouble("bearing", bearing.toDouble())
                putBoolean("movement", ConfigDraft.movement)
                putBoolean("wifi_shield", ConfigDraft.wifiShield)
                putBoolean("cell_shield", ConfigDraft.cellShield)
                putBoolean("mock_shield", ConfigDraft.mockShield)
                putBoolean("nr_enabled", ConfigDraft.nrEnabled)
                putBoolean("nmea_enabled", ConfigDraft.nmeaEnabled)
                putBoolean("agps_enabled", ConfigDraft.agpsEnabled)
                putBoolean("vendor_hooks_enabled", ConfigDraft.vendorHooksEnabled)
                putInt("gnss_sat_count", ConfigDraft.intOf(ConfigDraft.gnssSatText, Prefs.DEFAULT_GNSS_SAT_COUNT))
                putString("route_points", routeJson)
                putBoolean("route_loop", ConfigDraft.routeLoop)
                putBoolean("route_auto_bearing", ConfigDraft.routeAutoBearing)
                putBoolean("route_paused", routePaused)
                putLong("route_pause_elapsed_ms", pauseElapsed)
                putString("favorites", favoritesJson)
                putString("cell_mcc", ConfigDraft.mccText)
                putString("cell_mnc", ConfigDraft.mncText)
                putString("cell_country_iso", ConfigDraft.countryText)
                putString("cell_operator_name", ConfigDraft.operatorText)
                putString("last_status", if (active) "started" else "stopped")
            }
            val manager = getSystemService(Context.LOCATION_SERVICE)
            val method = LocationManager::class.java.getMethod(
                "sendExtraCommand",
                String::class.java,
                String::class.java,
                Bundle::class.java,
            )
            method.invoke(manager, "ghostloc", "sync", bundle)
            Log.i("GhostLocApp", "bridge sync sent active=$active")
        } catch (throwable: Throwable) {
            Log.i("GhostLocApp", "bridge sync failed " + throwable.javaClass.simpleName)
        }
    }

    private fun applyIntentSettings(remoteReady: Boolean) {
        val intent = intent ?: return
        val extras = intent.extras
        val lat = readDoubleExtra(EXTRA_LAT, Prefs.DEFAULT_LAT)
        val lon = readDoubleExtra(EXTRA_LON, Prefs.DEFAULT_LON)
        val active = intent.getBooleanExtra(EXTRA_ACTIVE, true)
        ConfigDraft.latText = formatDouble(lat, 6)
        ConfigDraft.lonText = formatDouble(lon, 6)
        if (extras != null) {
            if (extras.containsKey(EXTRA_MOVEMENT)) {
                ConfigDraft.movement = extras.getBoolean(EXTRA_MOVEMENT, ConfigDraft.movement)
            }
            if (extras.containsKey(EXTRA_ROUTE_LOOP)) {
                ConfigDraft.routeLoop = extras.getBoolean(EXTRA_ROUTE_LOOP, ConfigDraft.routeLoop)
            }
            if (extras.containsKey(EXTRA_ROUTE_AUTO_BEARING)) {
                ConfigDraft.routeAutoBearing = extras.getBoolean(EXTRA_ROUTE_AUTO_BEARING, ConfigDraft.routeAutoBearing)
            }
            if (extras.containsKey(EXTRA_NMEA_ENABLED)) {
                ConfigDraft.nmeaEnabled = extras.getBoolean(EXTRA_NMEA_ENABLED, ConfigDraft.nmeaEnabled)
            }
            if (extras.containsKey(EXTRA_VENDOR_HOOKS)) {
                ConfigDraft.vendorHooksEnabled = extras.getBoolean(EXTRA_VENDOR_HOOKS, ConfigDraft.vendorHooksEnabled)
            }
            if (extras.containsKey(EXTRA_NR_ENABLED)) {
                ConfigDraft.nrEnabled = extras.getBoolean(EXTRA_NR_ENABLED, ConfigDraft.nrEnabled)
            }
        }
        var routeJson = decodeBase64Extra(EXTRA_ROUTE_JSON_B64) ?: readStringExtra(EXTRA_ROUTE_JSON)
        if (routeJson != null) {
            ConfigDraft.routeJsonText = JsonModels.routeForDisplay(routeJson)
        }
        var favoritesJson = decodeBase64Extra(EXTRA_FAVORITES_JSON_B64) ?: readStringExtra(EXTRA_FAVORITES_JSON)
        if (favoritesJson != null) {
            ConfigDraft.favoritesJsonText = JsonModels.favoritesForDisplay(favoritesJson)
        }
        val routeVariation = readOptionalDoubleExtra(EXTRA_ROUTE_SPEED_VARIATION)
        if (routeVariation != null) {
            ConfigDraft.speedVariationText = formatDouble(routeVariation, 2)
        }
        commit(active, true, null)
        if (remoteReady) {
            pendingAutoApply = false
            if (intent.getBooleanExtra(EXTRA_FINISH, false)) {
                finish()
            }
        }
    }

    private fun readDoubleExtra(key: String, fallback: Double): Double {
        val value = intent?.extras?.get(key)
        if (value is Number) {
            return value.toDouble()
        }
        if (value is String) {
            return value.toDoubleOrNull() ?: fallback
        }
        return fallback
    }

    private fun readOptionalDoubleExtra(key: String): Double? {
        val extras = intent?.extras ?: return null
        if (!extras.containsKey(key)) {
            return null
        }
        return when (val value = extras.get(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun readStringExtra(key: String): String? {
        val value = intent?.extras?.get(key) ?: return null
        return value.toString()
    }

    private fun decodeBase64Extra(key: String): String? {
        val value = readStringExtra(key)
        if (value.isNullOrEmpty()) {
            return null
        }
        return try {
            String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestRuntimePermissions() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                42,
            )
        }
    }

    private fun formatDouble(value: Double, digits: Int): String {
        return String.format(java.util.Locale.US, "%." + digits + "f", value)
    }

    private fun formatDouble(value: Float, digits: Int): String = formatDouble(value.toDouble(), digits)

    private fun forEachFragment(action: (Fragment) -> Unit) {
        for (fragment in supportFragmentManager.fragments) {
            action(fragment)
        }
    }

    private class TabsAdapter(activity: MainActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> MapFragment()
                1 -> ParamsFragment()
                2 -> RouteFavoritesFragment()
                else -> DiagnosticsFragment()
            }
        }
    }
}
