package dev.ghostloc.offline.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.ghostloc.offline.R
import dev.ghostloc.offline.core.Prefs
import dev.ghostloc.offline.databinding.FragmentDiagnosticsBinding
import java.util.Locale

/** Diagnostics tab: framework/module state, recent hook evidence, probe runs. */
class DiagnosticsFragment : Fragment() {

    private var binding: FragmentDiagnosticsBinding? = null

    private val actions: UiActions?
        get() = activity as? UiActions

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        this.binding = binding

        binding.refreshButton.setOnClickListener { refreshDiagnostics() }
        binding.reloadButton.setOnClickListener {
            (activity as? MainActivityCallback)?.reloadFromPrefs()
        }
        binding.probeButton.setOnClickListener { actions?.launchProbe() }
        binding.applyProbeButton.setOnClickListener {
            actions?.commit(true, true, getString(R.string.toast_started))
            actions?.launchProbe()
        }
        binding.resetButton.setOnClickListener {
            (activity as? MainActivityCallback)?.resetDefaults()
        }
        binding.saveKeyButton.setOnClickListener {
            val key = binding.amapKeyInput.text.toString().trim()
            actions?.localPrefs()?.edit()?.putString(Prefs.UI_AMAP_SEARCH_KEY, key)?.apply()
            Toast.makeText(requireContext(), R.string.toast_key_saved, Toast.LENGTH_SHORT).show()
        }

        binding.amapKeyInput.setText(
            actions?.localPrefs()?.getString(Prefs.UI_AMAP_SEARCH_KEY, "").orEmpty()
        )
        refreshDiagnostics()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refreshDiagnostics()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    fun refreshDiagnostics() {
        val binding = binding ?: return
        val actions = actions ?: return
        val prefs = actions.prefs()
        val localPrefs = actions.localPrefs()
        val resources = requireContext().resources

        val favoriteCount = try {
            JsonModels.parseFavorites(prefs.getString(Prefs.FAVORITES, "")).size
        } catch (_: Throwable) {
            0
        }
        val routeCount = try {
            JsonModels.parseRoute(prefs.getString(Prefs.ROUTE_POINTS, "")).size
        } catch (_: Throwable) {
            0
        }
        val lastProcess = prefs.getString(Prefs.DIAG_LAST_PROCESS, "n/a")
        val hits = prefs.getString(Prefs.DIAG_RECENT_HITS, "none")
        val errors = prefs.getString(Prefs.DIAG_RECENT_ERRORS, "none")
        val probeReport = localPrefs.getString(Prefs.LAST_PROBE_REPORT, "无")
        val probeOk = localPrefs.getBoolean(Prefs.LAST_PROBE_OK, false)
        val probeAt = localPrefs.getLong(Prefs.LAST_PROBE_AT, 0L)
        val framework = (activity as? MainActivityCallback)?.frameworkDescription() ?: "未连接"

        val lastProbe = if (probeAt == 0L) {
            "无"
        } else {
            (if (probeOk) "通过 " else "失败 ") + String.format(Locale.US, "%1\$tH:%1\$tM:%1\$tS", probeAt)
        }

        val text = buildString {
            append(resources.getString(R.string.diag_framework_service, framework))
            append('\n')
            append(resources.getString(R.string.diag_map_source, TileSources.labelFor(ConfigDraft.tileSourceId, requireContext())))
            append('\n')
            append(
                resources.getString(
                    R.string.diag_enabled,
                    prefs.getBoolean(Prefs.ACTIVE, false),
                    prefs.getBoolean(Prefs.ROUTE_PAUSED, false),
                    describeLastStatus(prefs.getString(Prefs.LAST_STATUS, "idle")),
                )
            )
            append('\n')
            append(resources.getString(R.string.diag_recent_process, lastProcess))
            append('\n')
            append(resources.getString(R.string.diag_counts, favoriteCount, routeCount, prefs.getBoolean(Prefs.VENDOR_HOOKS_ENABLED, true)))
            append('\n')
            append(resources.getString(R.string.diag_last_probe, lastProbe))
            append('\n')
            append(resources.getString(R.string.diag_recommended_scope))
            append('\n')
            append(resources.getString(R.string.diag_optional_scope))
            append('\n')
            append(resources.getString(R.string.diag_probe_report, probeReport))
            append('\n')
            append(resources.getString(R.string.diag_recent_hits, hits))
            append('\n')
            append(resources.getString(R.string.diag_recent_errors, errors))
        }
        binding.diagnosticsText.text = text
    }

    private fun describeLastStatus(raw: String?): String {
        return when (raw) {
            null, "", "idle" -> "空闲"
            "route-paused" -> "路线已暂停"
            "route-resumed" -> "路线已继续"
            "reset" -> "已恢复默认"
            "started" -> "已启动"
            "stopped" -> "已停止"
            else -> raw
        }
    }
}

/** Extra hooks into the host activity that only diagnostics needs. */
interface MainActivityCallback {
    fun reloadFromPrefs()

    fun resetDefaults()

    fun frameworkDescription(): String
}
