package com.willwalsh.treker.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray

/** Saved places, kept on the phone. */
class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    val items = mutableStateListOf<Place>()

    init {
        runCatching {
            val arr = JSONArray(prefs.getString("list", "[]"))
            for (i in 0 until arr.length()) items.add(Place.fromJson(arr.getJSONObject(i)))
        }
    }

    fun contains(p: Place) = items.any { same(it, p) }

    fun add(p: Place): Boolean {
        if (contains(p)) return false
        items.add(p); save(); return true
    }

    fun remove(p: Place) { items.removeAll { same(it, p) }; save() }

    fun rename(p: Place, name: String) {
        val i = items.indexOfFirst { same(it, p) }
        if (i >= 0) { items[i] = items[i].copy(name = name); save() }
    }

    private fun same(a: Place, b: Place) =
        (a.placeId != null && a.placeId == b.placeId) || (a.name == b.name && Geo.distance(a.pos, b.pos) < 5)

    private fun save() {
        val arr = JSONArray(); items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
