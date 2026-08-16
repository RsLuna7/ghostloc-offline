package dev.ghostloc.offline.ui

import org.json.JSONArray
import org.json.JSONObject

/** Favorites and route JSON parsing/serialization shared by UI and diagnostics. */
object JsonModels {

    data class FavoriteEntry(val name: String, val group: String, val latitude: Double, val longitude: Double)
    data class RouteEntry(val name: String, val latitude: Double, val longitude: Double)

    class InvalidJsonException(message: String) : IllegalArgumentException(message)

    fun parseFavorites(raw: String?): MutableList<FavoriteEntry> {
        val favorites = ArrayList<FavoriteEntry>()
        if (raw.isNullOrBlank()) {
            return favorites
        }
        if (!raw.trim().startsWith("[")) {
            // Legacy "lat,lon;lat,lon" format.
            val items = raw.split(";")
            for ((index, item) in items.withIndex()) {
                val parts = item.split(",")
                if (parts.size >= 2) {
                    favorites.add(
                        FavoriteEntry(
                            "收藏点 " + (index + 1),
                            "历史",
                            parts[0].trim().toDouble(),
                            parts[1].trim().toDouble(),
                        )
                    )
                }
            }
            return favorites
        }
        return try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                favorites.add(
                    FavoriteEntry(
                        item.optString("name", "收藏点 " + (i + 1)),
                        item.optString("group", "默认"),
                        item.getDouble("lat"),
                        item.getDouble("lon"),
                    )
                )
            }
            favorites
        } catch (throwable: Throwable) {
            throw InvalidJsonException("favorites json")
        }
    }

    fun favoritesToJson(favorites: List<FavoriteEntry>): String {
        return try {
            val array = JSONArray()
            for (favorite in favorites) {
                val object_ = JSONObject()
                object_.put("name", favorite.name)
                object_.put("group", favorite.group)
                object_.put("lat", favorite.latitude)
                object_.put("lon", favorite.longitude)
                array.put(object_)
            }
            array.toString()
        } catch (throwable: Throwable) {
            throw InvalidJsonException("favorites serialize")
        }
    }

    fun parseRoute(raw: String?): MutableList<RouteEntry> {
        val route = ArrayList<RouteEntry>()
        if (raw.isNullOrBlank()) {
            return route
        }
        return try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                route.add(RouteEntry(item.optString("name", "点位 " + (i + 1)), item.getDouble("lat"), item.getDouble("lon")))
            }
            route
        } catch (throwable: Throwable) {
            throw InvalidJsonException("route json")
        }
    }

    fun routeToJson(route: List<RouteEntry>): String {
        return try {
            val array = JSONArray()
            for (entry in route) {
                val object_ = JSONObject()
                object_.put("name", entry.name)
                object_.put("lat", entry.latitude)
                object_.put("lon", entry.longitude)
                array.put(object_)
            }
            array.toString()
        } catch (throwable: Throwable) {
            throw InvalidJsonException("route serialize")
        }
    }

    fun favoritesForDisplay(raw: String?): String {
        return try {
            JSONArray(normalizeFavorites(raw)).toString(2)
        } catch (_: Throwable) {
            "[]"
        }
    }

    fun routeForDisplay(raw: String?): String {
        return try {
            JSONArray(normalizeRoute(raw)).toString(2)
        } catch (_: Throwable) {
            "[]"
        }
    }

    fun normalizeFavorites(raw: String?): String = favoritesToJson(parseFavorites(raw))

    fun normalizeRoute(raw: String?): String = routeToJson(parseRoute(raw))
}
