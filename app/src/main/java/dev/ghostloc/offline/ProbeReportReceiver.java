package dev.ghostloc.offline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public final class ProbeReportReceiver extends BroadcastReceiver {
    public static final String ACTION_PROBE_REPORT = "dev.ghostloc.offline.action.PROBE_REPORT";
    public static final String EXTRA_REPORT = "probe_report";
    public static final String EXTRA_OK = "probe_ok";
    public static final String EXTRA_AT = "probe_at";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_PROBE_REPORT.equals(intent.getAction())) {
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences(SettingsStore.GROUP, Context.MODE_PRIVATE);
        String report = intent.getStringExtra(EXTRA_REPORT);
        boolean ok = intent.getBooleanExtra(EXTRA_OK, false);
        long at = intent.getLongExtra(EXTRA_AT, System.currentTimeMillis());
        preferences.edit()
                .putString(SettingsStore.LAST_PROBE_REPORT, report == null ? "" : report)
                .putBoolean(SettingsStore.LAST_PROBE_OK, ok)
                .putLong(SettingsStore.LAST_PROBE_AT, at)
                .apply();
        Log.i("GhostLocApp", "probe report stored ok=" + ok);
    }
}
