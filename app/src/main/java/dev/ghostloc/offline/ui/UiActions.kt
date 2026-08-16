package dev.ghostloc.offline.ui

import android.content.SharedPreferences

/** Actions the host activity exposes to the tab fragments. */
interface UiActions {
    /** Remote "settings" group when the module service is bound, local fallback otherwise. */
    fun prefs(): SharedPreferences

    /** Local "settings" group, used for probe reports and UI-only keys. */
    fun localPrefs(): SharedPreferences

    fun commit(active: Boolean, resetTimer: Boolean, toast: String?)

    fun isActive(): Boolean

    fun pauseRoute()

    fun resumeRoute()

    fun launchProbe()

    fun refreshAll()
}
