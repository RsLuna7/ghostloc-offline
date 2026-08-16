package dev.ghostloc.offline.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.ghostloc.offline.R
import dev.ghostloc.offline.core.GeoMath
import dev.ghostloc.offline.core.Prefs
import dev.ghostloc.offline.databinding.FragmentMapBinding
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

/**
 * Map tab: tile-source picker (AMap road/satellite reachable in mainland
 * China, OSM as alternative), search, tap-to-pick with GCJ-02 correction, and
 * quick start/stop. The map fills the page with floating controls; the marker
 * is draggable for fine-tuning.
 */
class MapFragment : Fragment(), ConfigDraft.Listener {

    private var binding: FragmentMapBinding? = null
    private var mapView: MapView? = null
    private var marker: Marker? = null
    private var suppressTileSpinnerSync = false
    private var centered = false

    private val actions: UiActions?
        get() = activity as? UiActions

    private val localPrefs: SharedPreferences?
        get() = actions?.localPrefs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentMapBinding.inflate(inflater, container, false)
        this.binding = binding
        val context = requireContext()

        val map = binding.mapView
        mapView = map
        map.setMultiTouchControls(true)
        // DPI-scaled tiles request a lower zoom level and upscale it: blurry
        // on dense screens and visibly chunky while panning. Native-resolution
        // tiles stay crisp; the memory cost is bounded by the small cache.
        map.setTilesScaledToDpi(false)
        map.isFlingEnabled = true
        map.controller.setZoom(14.0)

        val tileLabels = TileSources.SOURCES.map { TileSources.labelFor(it.id, context) }
        binding.tileSourceSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, tileLabels)
        binding.tileSourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressTileSpinnerSync) {
                    return
                }
                val source = TileSources.SOURCES[position]
                ConfigDraft.tileSourceId = source.id
                localPrefs?.edit()?.putString(Prefs.UI_TILE_SOURCE, source.id)?.apply()
                applyTileSource()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.pickupCorrectCheck.setOnCheckedChangeListener { _, checked ->
            ConfigDraft.pickupGcj02Correct = checked
            localPrefs?.edit()?.putBoolean(Prefs.UI_PICKUP_GCJ02_CORRECT, checked)?.apply()
        }

        val marker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = true
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) {}

                override fun onMarkerDragEnd(marker: Marker) {
                    pickPoint(marker.position, start = false)
                }

                override fun onMarkerDragStart(marker: Marker) {}
            })
        }
        map.overlays.add(marker)
        this.marker = marker

        map.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    p ?: return false
                    pickPoint(p, start = false)
                    return true
                }

                override fun longPressHelper(p: GeoPoint?): Boolean {
                    p ?: return false
                    pickPoint(p, start = true)
                    return true
                }
            })
        )

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchPlace()
                true
            } else {
                false
            }
        }
        binding.searchButton.setOnClickListener { searchPlace() }
        binding.zoomInButton.setOnClickListener { map.controller.zoomIn() }
        binding.zoomOutButton.setOnClickListener { map.controller.zoomOut() }
        binding.recenterButton.setOnClickListener { recenterOnMarker() }
        binding.startButton.setOnClickListener { actions?.commit(true, true, getString(R.string.toast_started)) }
        binding.stopButton.setOnClickListener { actions?.commit(false, true, getString(R.string.toast_stopped)) }
        binding.saveButton.setOnClickListener {
            actions?.commit(actions?.isActive() == true, false, getString(R.string.toast_saved))
        }

        syncFromDraft()
        ConfigDraft.addListener(this)
        return binding.root
    }

    override fun onDestroyView() {
        ConfigDraft.removeListener(this)
        binding?.mapView?.onDetach()
        mapView = null
        marker = null
        binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onDraftChanged(source: Any?) {
        syncFromDraft()
    }

    private fun syncFromDraft() {
        val binding = binding ?: return
        suppressTileSpinnerSync = true
        val index = TileSources.SOURCES.indexOfFirst { it.id == ConfigDraft.tileSourceId }.let {
            if (it >= 0) it else 0
        }
        binding.tileSourceSpinner.setSelection(index)
        suppressTileSpinnerSync = false
        if (binding.pickupCorrectCheck.isChecked != ConfigDraft.pickupGcj02Correct) {
            binding.pickupCorrectCheck.isChecked = ConfigDraft.pickupGcj02Correct
        }
        applyTileSource()
        updateMarker(recenter = false)
    }

    private fun applyTileSource() {
        val map = mapView ?: return
        val source = TileSources.byId(ConfigDraft.tileSourceId)
        val next = source.factory()
        android.util.Log.i(
            "GhostLocApp",
            "applyTileSource id=${source.id} next=${next.name()} current=${map.tileProvider.tileSource.name()}" +
                " cache=" + org.osmdroid.config.Configuration.getInstance().osmdroidTileCache,
        )
        if (map.tileProvider.tileSource.name() != next.name()) {
            map.setTileSource(next)
        }
        map.invalidate()
        updateMarker(recenter = false)
    }

    private fun pickPoint(picked: GeoPoint, start: Boolean) {
        val source = TileSources.byId(ConfigDraft.tileSourceId)
        var lat = picked.latitude
        var lon = picked.longitude
        if (source.isGcj02 && ConfigDraft.pickupGcj02Correct) {
            val corrected = GeoMath.gcj02ToWgs84(lat, lon)
            lat = corrected[0]
            lon = corrected[1]
        }
        ConfigDraft.latText = String.format(Locale.US, "%.6f", lat)
        ConfigDraft.lonText = String.format(Locale.US, "%.6f", lon)
        ConfigDraft.notifyChanged(this)
        if (start) {
            actions?.commit(true, true, getString(R.string.toast_started))
        }
    }

    /**
     * Moves the shared marker without touching the viewport: callers that need
     * to bring the point into view (search, explicit recenter) animate the
     * camera themselves, so background draft syncs never yank the map out from
     * under the user's finger.
     */
    private fun updateMarker(recenter: Boolean) {
        val map = mapView ?: return
        val marker = marker ?: return
        val lat = ConfigDraft.latOr()
        val lon = ConfigDraft.lonOr()
        val source = TileSources.byId(ConfigDraft.tileSourceId)
        val display = if (source.isGcj02) GeoMath.wgs84ToGcj02(lat, lon) else doubleArrayOf(lat, lon)
        val point = GeoPoint(display[0], display[1])
        marker.position = point
        marker.title = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        if (!centered) {
            // first placement on a fresh MapView: show the point once
            centered = true
            map.post {
                map.controller.setCenter(point)
                map.invalidate()
            }
        } else if (recenter) {
            map.controller.animateTo(point)
        }
        map.invalidate()
    }

    private fun recenterOnMarker() {
        val map = mapView ?: return
        val lat = ConfigDraft.latOr()
        val lon = ConfigDraft.lonOr()
        val source = TileSources.byId(ConfigDraft.tileSourceId)
        val display = if (source.isGcj02) GeoMath.wgs84ToGcj02(lat, lon) else doubleArrayOf(lat, lon)
        map.controller.animateTo(GeoPoint(display[0], display[1]))
    }

    private fun searchPlace() {
        val binding = binding ?: return
        val query = binding.searchInput.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(requireContext(), R.string.toast_search_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), R.string.toast_searching, Toast.LENGTH_SHORT).show()
        val key = localPrefs?.getString(Prefs.UI_AMAP_SEARCH_KEY, "")?.trim().orEmpty().ifBlank { null }
        PlaceSearch.search(requireContext(), query, key) { result, error ->
            val context = context ?: return@search
            when {
                result != null -> {
                    ConfigDraft.latText = String.format(Locale.US, "%.6f", result[0])
                    ConfigDraft.lonText = String.format(Locale.US, "%.6f", result[1])
                    ConfigDraft.notifyChanged(this)
                    recenterOnMarker()
                    Toast.makeText(context, R.string.toast_search_found, Toast.LENGTH_SHORT).show()
                }
                error == "no_geocoder" ->
                    Toast.makeText(context, R.string.toast_no_geocoder, Toast.LENGTH_LONG).show()
                error == "search_failed" ->
                    Toast.makeText(context, R.string.toast_search_failed, Toast.LENGTH_SHORT).show()
                else ->
                    Toast.makeText(context, R.string.toast_search_no_result, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
