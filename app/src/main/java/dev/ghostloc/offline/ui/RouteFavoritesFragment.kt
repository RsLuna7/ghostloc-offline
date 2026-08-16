package dev.ghostloc.offline.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.ghostloc.offline.R
import dev.ghostloc.offline.core.Prefs
import dev.ghostloc.offline.databinding.FragmentRouteBinding

/** Route & favorites tab: JSON editors plus movement controls. */
class RouteFavoritesFragment : Fragment(), ConfigDraft.Listener {

    private var binding: FragmentRouteBinding? = null
    private var syncing = false

    private val actions: UiActions?
        get() = activity as? UiActions

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentRouteBinding.inflate(inflater, container, false)
        this.binding = binding

        bindText(binding.routeJsonInput) { ConfigDraft.routeJsonText = it }
        bindText(binding.favoritesJsonInput) { ConfigDraft.favoritesJsonText = it }
        bindCheck(binding.routeLoopCheck) { ConfigDraft.routeLoop = it }
        bindCheck(binding.routeAutoBearingCheck) { ConfigDraft.routeAutoBearing = it }

        binding.addPointButton.setOnClickListener { addRoutePoint() }
        binding.saveRouteButton.setOnClickListener { saveRouteFromEditor() }
        binding.useRouteStartButton.setOnClickListener { useRouteStart() }
        binding.clearRouteButton.setOnClickListener { clearRoute() }
        binding.pauseButton.setOnClickListener { actions?.pauseRoute() }
        binding.resumeButton.setOnClickListener { actions?.resumeRoute() }
        binding.saveConfigButton.setOnClickListener {
            actions?.commit(actions?.isActive() == true, false, getString(R.string.toast_saved))
        }
        binding.addFavoriteButton.setOnClickListener { addFavorite() }
        binding.saveFavoritesButton.setOnClickListener { saveFavoritesFromEditor() }
        binding.useFavoriteButton.setOnClickListener { useLastFavorite() }
        binding.clearFavoritesButton.setOnClickListener { clearFavorites() }

        syncFromDraft()
        ConfigDraft.addListener(this)
        return binding.root
    }

    override fun onDestroyView() {
        ConfigDraft.removeListener(this)
        binding = null
        super.onDestroyView()
    }

    override fun onDraftChanged(source: Any?) {
        syncFromDraft()
    }

    fun syncFromDraft() {
        val binding = binding ?: return
        syncing = true
        setTextIfDifferent(binding.routeJsonInput, ConfigDraft.routeJsonText)
        setTextIfDifferent(binding.favoritesJsonInput, ConfigDraft.favoritesJsonText)
        binding.routeLoopCheck.isChecked = ConfigDraft.routeLoop
        binding.routeAutoBearingCheck.isChecked = ConfigDraft.routeAutoBearing
        syncing = false
    }

    // ---- route actions ----

    private fun addRoutePoint() {
        val context = context ?: return
        try {
            val route = JsonModels.parseRoute(JsonModels.normalizeRoute(ConfigDraft.routeJsonText))
            route.add(
                JsonModels.RouteEntry(
                    "点位 " + (route.size + 1),
                    ConfigDraft.latOr(),
                    ConfigDraft.lonOr(),
                )
            )
            saveRoute(route, getString(R.string.toast_route_point_saved))
        } catch (_: Throwable) {
            Toast.makeText(context, R.string.toast_route_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveRouteFromEditor() {
        val context = context ?: return
        try {
            saveRoute(JsonModels.parseRoute(JsonModels.normalizeRoute(ConfigDraft.routeJsonText)), getString(R.string.toast_route_saved))
        } catch (_: Throwable) {
            Toast.makeText(context, R.string.toast_route_json_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveRoute(route: List<JsonModels.RouteEntry>, toastText: String) {
        val json = JsonModels.routeToJson(route)
        actions?.prefs()?.edit()?.putString(Prefs.ROUTE_POINTS, json)?.apply()
        ConfigDraft.routeJsonText = JsonModels.routeForDisplay(json)
        syncFromDraft()
        actions?.refreshAll()
        Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
    }

    private fun useRouteStart() {
        val route = JsonModels.parseRoute(actions?.prefs()?.getString(Prefs.ROUTE_POINTS, ""))
        if (route.isEmpty()) {
            Toast.makeText(requireContext(), R.string.toast_route_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val entry = route[0]
        setCoordinate(entry.latitude, entry.longitude)
        actions?.commit(actions?.isActive() == true, false, getString(R.string.toast_saved))
    }

    private fun clearRoute() {
        actions?.prefs()?.edit()
            ?.remove(Prefs.ROUTE_POINTS)
            ?.putBoolean(Prefs.ROUTE_PAUSED, false)
            ?.putLong(Prefs.ROUTE_PAUSE_ELAPSED_MS, 0L)
            ?.apply()
        ConfigDraft.routeJsonText = "[]"
        syncFromDraft()
        actions?.refreshAll()
        Toast.makeText(requireContext(), R.string.toast_route_cleared, Toast.LENGTH_SHORT).show()
    }

    // ---- favorites actions ----

    private fun addFavorite() {
        val context = context ?: return
        try {
            val favorites = JsonModels.parseFavorites(JsonModels.normalizeFavorites(ConfigDraft.favoritesJsonText))
            favorites.add(
                JsonModels.FavoriteEntry(
                    "收藏点 " + (favorites.size + 1),
                    ConfigDraft.countryText.ifBlank { "默认" },
                    ConfigDraft.latOr(),
                    ConfigDraft.lonOr(),
                )
            )
            saveFavorites(favorites, getString(R.string.toast_favorite_point_saved))
        } catch (_: Throwable) {
            Toast.makeText(context, R.string.toast_favorites_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFavoritesFromEditor() {
        val context = context ?: return
        try {
            saveFavorites(
                JsonModels.parseFavorites(JsonModels.normalizeFavorites(ConfigDraft.favoritesJsonText)),
                getString(R.string.toast_favorite_saved),
            )
        } catch (_: Throwable) {
            Toast.makeText(context, R.string.toast_favorites_json_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFavorites(favorites: List<JsonModels.FavoriteEntry>, toastText: String) {
        val json = JsonModels.favoritesToJson(favorites)
        actions?.prefs()?.edit()?.putString(Prefs.FAVORITES, json)?.apply()
        ConfigDraft.favoritesJsonText = JsonModels.favoritesForDisplay(json)
        syncFromDraft()
        actions?.refreshAll()
        Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
    }

    private fun useLastFavorite() {
        val favorites = JsonModels.parseFavorites(actions?.prefs()?.getString(Prefs.FAVORITES, ""))
        if (favorites.isEmpty()) {
            Toast.makeText(requireContext(), R.string.toast_favorites_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val entry = favorites[favorites.size - 1]
        setCoordinate(entry.latitude, entry.longitude)
        actions?.commit(actions?.isActive() == true, false, getString(R.string.toast_saved))
    }

    private fun clearFavorites() {
        actions?.prefs()?.edit()?.remove(Prefs.FAVORITES)?.apply()
        ConfigDraft.favoritesJsonText = "[]"
        syncFromDraft()
        actions?.refreshAll()
        Toast.makeText(requireContext(), R.string.toast_favorites_cleared, Toast.LENGTH_SHORT).show()
    }

    // ---- helpers ----

    private fun setCoordinate(lat: Double, lon: Double) {
        ConfigDraft.latText = String.format(java.util.Locale.US, "%.6f", lat)
        ConfigDraft.lonText = String.format(java.util.Locale.US, "%.6f", lon)
        ConfigDraft.notifyChanged(this)
    }

    private fun bindText(input: EditText, assign: (String) -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (syncing) {
                    return
                }
                assign(s?.toString()?.trim().orEmpty())
            }
        })
    }

    private fun bindCheck(check: android.widget.CompoundButton, assign: (Boolean) -> Unit) {
        check.setOnCheckedChangeListener { _, isChecked ->
            if (syncing) {
                return@setOnCheckedChangeListener
            }
            assign(isChecked)
        }
    }

    private fun setTextIfDifferent(input: EditText, value: String) {
        if (input.text.toString() != value) {
            input.setText(value)
        }
    }
}
