package dev.ghostloc.offline.core

import android.content.SharedPreferences
import android.location.LocationManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

/**
 * Live state channel between the UI and the hook layer.
 *
 * On this framework (Vector) the hook-side remote preferences are read-only
 * and UI-side service writes only reach a database the hook layer never
 * reloads, so preference writes from the app never become visible to hooks
 * through those APIs. Instead, the UI pushes a full "sync" command through
 * `LocationManager.sendExtraCommand`; the hook on the system_server side of
 * that call persists the state to this file and serves every location
 * request from it, so all apps (their binder calls hit system_server) see
 * live values. The file also survives reboots.
 *
 * Only system_server can access the file (SELinux); other processes fall
 * back to the boot-time remote preferences for the client-only hooks
 * (GNSS/NMEA/Wi-Fi/cell), whose configuration changes apply after the target
 * app restarts.
 */
object StateStore {

    private const val TAG = "GhostLocState"
    private const val DIR = "/data/misc/ghostloc"
    private const val FILE_NAME = "state.properties"

    private val file: File get() = File(DIR, FILE_NAME)

    private var fileProps: Properties? = null
    private var fileMtime = 0L
    private var fileSize = 0L

    /** Current overlay for this process (empty when the file is unreadable). */
    fun props(): Properties = readFromFile() ?: Properties()

    private fun readFromFile(): Properties? {
        return try {
            val f = file
            val mtime = f.lastModified()
            val size = f.length()
            val cached = fileProps
            if (cached != null && mtime == fileMtime && size == fileSize) {
                return cached
            }
            val loaded = Properties()
            f.inputStream().use { loaded.load(it) }
            fileProps = loaded
            fileMtime = mtime
            fileSize = size
            loaded
        } catch (_: Throwable) {
            // No permission (app process) or no file yet.
            null
        }
    }

    /**
     * Applies [mutations] to the current state and persists it. Must run with
     * privileges for /data/misc (system_server via the bridge hook).
     */
    fun update(mutations: Map<String, String>): Boolean {
        val merged = readFromFile() ?: Properties()
        for ((key, value) in mutations) {
            merged.setProperty(key, value)
        }
        return write(merged)
    }

    private fun write(props: Properties): Boolean {
        return try {
            val dir = File(DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val target = file
            val tmp = File(DIR, FILE_NAME + ".tmp")
            tmp.outputStream().use { props.store(it, null) }
            if (target.exists()) {
                target.delete()
            }
            val ok = tmp.renameTo(target)
            if (ok) {
                fileProps = props
                fileMtime = target.lastModified()
                fileSize = target.length()
            } else {
                Log.w(TAG, "rename failed")
            }
            ok
        } catch (throwable: Throwable) {
            Log.w(TAG, "write failed: " + throwable.javaClass.simpleName)
            false
        }
    }
}

/**
 * SharedPreferences view used by the hook layer: values from the live
 * [StateStore] win over the boot-time remote preferences. All values are
 * stored as strings in the overlay and parsed per requested type.
 */
class MergedPrefs(
    private val remote: SharedPreferences,
) : SharedPreferences {

    private fun overlay(key: String): String? = StateStore.props().getProperty(key)

    private fun hasOverlay(key: String): Boolean = StateStore.props().containsKey(key)

    override fun getAll(): Map<String, *> {
        val result = HashMap<String, Any?>(remote.all)
        for ((key, value) in StateStore.props()) {
            result[key as String] = value
        }
        return result
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (key != null && hasOverlay(key)) return overlay(key) ?: defValue
        return remote.getString(key, defValue)
    }

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
        if (key != null && hasOverlay(key)) {
            val raw = overlay(key) ?: return defValues
            return raw.split(",").filter { it.isNotEmpty() }.toSet()
        }
        return remote.getStringSet(key, defValues)
    }

    override fun getInt(key: String?, defValue: Int): Int {
        if (key != null && hasOverlay(key)) {
            return overlay(key)?.toIntOrNull() ?: defValue
        }
        return remote.getInt(key, defValue)
    }

    override fun getLong(key: String?, defValue: Long): Long {
        if (key != null && hasOverlay(key)) {
            return overlay(key)?.toLongOrNull() ?: defValue
        }
        return remote.getLong(key, defValue)
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        if (key != null && hasOverlay(key)) {
            return overlay(key)?.toFloatOrNull() ?: defValue
        }
        return remote.getFloat(key, defValue)
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (key != null && hasOverlay(key)) {
            return overlay(key)?.toBoolean() ?: defValue
        }
        return remote.getBoolean(key, defValue)
    }

    override fun contains(key: String?): Boolean {
        if (key != null && hasOverlay(key)) return true
        return remote.contains(key)
    }

    /** Not supported: writes go through [Bridge]/[StateStore], never here. */
    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("read-only merged view")

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
