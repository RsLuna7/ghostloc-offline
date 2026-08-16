package dev.ghostloc.offline.ui

import android.content.Context
import android.location.Geocoder
import android.util.Log
import dev.ghostloc.offline.core.GeoMath
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Place search: AMap Web Service when a key is configured (works on Chinese
 * ROMs without GMS), system Geocoder as fallback. Results are returned in
 * WGS-84 so they can feed the hook layer directly.
 */
object PlaceSearch {

    private const val TAG = "GhostLocApp"
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ghostloc-search")
    }

    fun interface Callback {
        /** Called on the main thread with a WGS-84 coordinate, or null on miss. */
        fun onResult(latLon: DoubleArray?, errorMessage: String?)
    }

    fun search(context: Context, query: String, amapKey: String?, callback: Callback) {
        val appContext = context.applicationContext
        executor.execute {
            var result: DoubleArray? = null
            var error: String? = null
            try {
                if (!amapKey.isNullOrBlank()) {
                    result = searchAmap(query, amapKey.trim())
                    if (result == null) {
                        error = "amap_no_result"
                    }
                } else if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(appContext, java.util.Locale.SIMPLIFIED_CHINESE)
                    val addresses = geocoder.getFromLocationName(query, 1)
                    if (addresses != null && addresses.isNotEmpty()) {
                        val address = addresses[0]
                        result = doubleArrayOf(address.latitude, address.longitude)
                    } else {
                        error = "geocoder_no_result"
                    }
                } else {
                    error = "no_geocoder"
                }
            } catch (throwable: Throwable) {
                Log.i(TAG, "search failed: " + throwable.javaClass.simpleName)
                error = "search_failed"
            }
            val finalResult = result
            val finalError = error
            android.os.Handler(appContext.mainLooper).post {
                callback.onResult(finalResult, finalError)
            }
        }
    }

    /** AMap POI text search; the returned location is GCJ-02 and converted to WGS-84. */
    private fun searchAmap(query: String, key: String): DoubleArray? {
        val url = "https://restapi.amap.com/v3/place/text" +
            "?key=" + URLEncoder.encode(key, "UTF-8") +
            "&keywords=" + URLEncoder.encode(query, "UTF-8") +
            "&offset=1&page=1&extensions=base"
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "GhostLoc-Offline/0.2")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "amap search http=$code")
                return null
            }
            val body = connection.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                input.copyTo(buffer)
                buffer.toString("UTF-8")
            }
            val json = JSONObject(body)
            if (json.optString("status") != "1") {
                Log.i(TAG, "amap search status=" + json.optString("status") + " info=" + json.optString("info"))
                return null
            }
            val pois = json.optJSONArray("pois") ?: return null
            if (pois.length() == 0) {
                return null
            }
            val location = pois.getJSONObject(0).optString("location")
            val parts = location.split(",")
            if (parts.size < 2) {
                return null
            }
            val gcjLon = parts[0].trim().toDoubleOrNull() ?: return null
            val gcjLat = parts[1].trim().toDoubleOrNull() ?: return null
            GeoMath.gcj02ToWgs84(gcjLat, gcjLon)
        } finally {
            connection.disconnect()
        }
    }
}
