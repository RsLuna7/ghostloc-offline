package dev.ghostloc.offline

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.osmdroid.config.Configuration
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

class ModuleApplication : Application(), XposedServiceHelper.OnServiceListener {

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        initOsmdroid()
        XposedServiceHelper.registerListener(this)
    }

    /**
     * Must run before any MapView is inflated: the tile provider chain reads
     * the cache path and user agent once, at construction time. The cache
     * lives in app-internal storage so no storage permission is needed and
     * tiles survive reboots.
     */
    private fun initOsmdroid() {
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(filesDir, "osmdroid/tiles")
            isDebugMapTileDownloader = true
        }
    }

    override fun onServiceBind(service: XposedService) {
        boundService = service
        Log.i(TAG, "service bound framework=${service.frameworkName} api=${service.apiVersion}")
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        boundService = null
        Log.i(TAG, "service died")
        dispatch(null)
    }

    companion object {
        private const val TAG = "GhostLocApp"
        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()

        @Volatile
        private var boundService: XposedService? = null

        @JvmStatic
        fun currentService(): XposedService? = boundService

        @JvmStatic
        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                listener.onServiceStateChanged(boundService)
            }
        }

        @JvmStatic
        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            for (listener in listeners) {
                listener.onServiceStateChanged(service)
            }
        }
    }
}
