package dev.ghostloc.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ghostloc.offline.core.Prefs

class ProbeReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PROBE_REPORT) {
            return
        }
        val ok = intent.getBooleanExtra(EXTRA_OK, false)
        context.getSharedPreferences(Prefs.GROUP, Context.MODE_PRIVATE).edit()
            .putString(Prefs.LAST_PROBE_REPORT, intent.getStringExtra(EXTRA_REPORT) ?: "")
            .putBoolean(Prefs.LAST_PROBE_OK, ok)
            .putLong(Prefs.LAST_PROBE_AT, intent.getLongExtra(EXTRA_AT, System.currentTimeMillis()))
            .apply()
        Log.i("GhostLocApp", "probe report stored ok=$ok")
    }

    companion object {
        const val ACTION_PROBE_REPORT = "dev.ghostloc.offline.action.PROBE_REPORT"
        const val EXTRA_REPORT = "probe_report"
        const val EXTRA_OK = "probe_ok"
        const val EXTRA_AT = "probe_at"
    }
}
