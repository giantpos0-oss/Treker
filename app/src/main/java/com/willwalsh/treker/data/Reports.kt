package com.willwalsh.treker.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.willwalsh.treker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What kind of map problem someone is reporting. */
enum class Problem(val label: String) {
    PLACE_MISSING("A place is missing"),
    PLACE_WRONG("A place is in the wrong spot, closed or gone"),
    PLACE_NAME("A place's name or type is wrong"),
    STREET_NAME("A street name is wrong"),
    INTERSECTION("An intersection is wrong or missing"),
    ADDRESS("An address, city or zip code is wrong"),
    PATH("A sidewalk, crossing or path is wrong or missing"),
    ROUTE("The route was wrong or unsafe"),
    OTHER("Something else"),
}

/** One report. Kept on the phone until OpenStreetMap accepts it. */
data class Report(
    val problem: Problem,
    val pos: LatLon,
    val details: String,
    val placeName: String?,
    val placeKey: String?,
    /** What Treker said or showed here, so a mapper knows what the person heard. */
    val heard: String?,
    val created: Long = System.currentTimeMillis(),
    var noteId: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("problem", problem.name).put("lat", pos.lat).put("lon", pos.lon)
        .put("details", details).put("placeName", placeName ?: "").put("placeKey", placeKey ?: "")
        .put("heard", heard ?: "").put("created", created).put("noteId", noteId ?: 0L)

    companion object {
        fun fromJson(o: JSONObject) = Report(
            Problem.valueOf(o.getString("problem")), LatLon(o.getDouble("lat"), o.getDouble("lon")),
            o.optString("details"), o.optString("placeName").ifBlank { null }, o.optString("placeKey").ifBlank { null },
            o.optString("heard").ifBlank { null }, o.optLong("created"), o.optLong("noteId").takeIf { it > 0 },
        )
    }
}

/**
 * Map problem reports. Each becomes a public OpenStreetMap note at the spot, which local
 * volunteer mappers see and fix; the fix reaches Treker with the next monthly map update.
 * Reports made without internet, or that OpenStreetMap doesn't accept yet, wait and are retried.
 */
object Reports {
    val items = mutableStateListOf<Report>()
    private lateinit var prefsFile: android.content.SharedPreferences

    fun init(context: Context) {
        prefsFile = context.getSharedPreferences("reports", Context.MODE_PRIVATE)
        runCatching {
            val arr = JSONArray(prefsFile.getString("list", "[]"))
            for (i in 0 until arr.length()) items.add(Report.fromJson(arr.getJSONObject(i)))
        }
    }

    private fun save() {
        val arr = JSONArray(); items.takeLast(200).forEach { arr.put(it.toJson()) }
        prefsFile.edit().putString("list", arr.toString()).apply()
    }

    val waiting get() = items.count { it.noteId == null }

    /** Saves the report and tries to send it. Returns true if OpenStreetMap accepted it now. */
    suspend fun submit(r: Report): Boolean {
        items.add(r); save()
        return send(r)
    }

    /** Sends every report still waiting. Returns how many went through. */
    suspend fun sendWaiting(): Int {
        var n = 0
        for (r in items.filter { it.noteId == null }) if (send(r)) n++
        return n
    }

    private suspend fun send(r: Report): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = "lat=${r.pos.lat}&lon=${r.pos.lon}&text=" + URLEncoder.encode(noteText(r), "UTF-8")
            val c = (URL("https://api.openstreetmap.org/api/0.6/notes.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 15_000; readTimeout = 20_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "Treker/${BuildConfig.VERSION_NAME} (Android GPS for blind people)")
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) return@runCatching false
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            r.noteId = json.getJSONObject("properties").getLong("id")
            withContext(Dispatchers.Main) {
                val i = items.indexOf(r); if (i >= 0) items[i] = r.copy()
                save()
            }
            true
        }.getOrDefault(false)
    }

    /** The note mappers read. Plain words, no links (OpenStreetMap is strict with anonymous notes). */
    fun noteText(r: Report): String = buildString {
        append(r.problem.label).append(".")
        if (r.details.isNotBlank()) append("\n\n").append(r.details.trim())
        if (r.placeName != null) {
            append("\n\nPlace: ").append(r.placeName)
            r.placeKey?.removePrefix("osm:")?.takeIf { r.placeKey.startsWith("osm:") }?.let { append(" (OSM ").append(it).append(")") }
        }
        if (!r.heard.isNullOrBlank()) append("\n\nThe app said: ").append(r.heard.take(300))
        append("\n\nReported by a blind person using Treker, a GPS app for blind people that uses OpenStreetMap ")
        append("to announce streets, intersections and places. Accurate data here helps them travel safely. #Treker")
    }

    fun noteLink(r: Report) = r.noteId?.let { "https://www.openstreetmap.org/note/$it" }
}

/** A fix someone made on their own phone, used straight away, before the map itself is corrected. */
data class MapCorrection(
    val type: Type,
    val name: String,
    val newName: String,
    val pos: LatLon,
    val placeKey: String?,
    val created: Long = System.currentTimeMillis(),
) {
    enum class Type { HIDE_PLACE, RENAME_PLACE, ADD_PLACE, RENAME_STREET }

    fun spoken(): String = when (type) {
        Type.HIDE_PLACE -> "Hid $name"
        Type.RENAME_PLACE -> "Renamed $name to $newName"
        Type.ADD_PLACE -> "Added $newName"
        Type.RENAME_STREET -> "Renamed street $name to $newName"
    }

    fun toJson(): JSONObject = JSONObject().put("type", type.name).put("name", name).put("newName", newName)
        .put("lat", pos.lat).put("lon", pos.lon).put("placeKey", placeKey ?: "").put("created", created)

    companion object {
        fun fromJson(o: JSONObject) = MapCorrection(
            Type.valueOf(o.getString("type")), o.getString("name"), o.getString("newName"),
            LatLon(o.getDouble("lat"), o.getDouble("lon")), o.optString("placeKey").ifBlank { null }, o.optLong("created"),
        )
    }
}

object Corrections {
    val items = mutableStateListOf<MapCorrection>()
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("corrections", Context.MODE_PRIVATE)
        runCatching {
            val arr = JSONArray(prefs.getString("list", "[]"))
            for (i in 0 until arr.length()) items.add(MapCorrection.fromJson(arr.getJSONObject(i)))
        }
    }

    fun add(c: MapCorrection) { items.add(c); save() }
    fun remove(c: MapCorrection) { items.remove(c); save() }

    private fun save() {
        val arr = JSONArray(); items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun matches(c: MapCorrection, p: Place): Boolean =
        (c.placeKey != null && c.placeKey == p.placeId) ||
            (c.name.equals(p.name, ignoreCase = true) && Geo.distance(c.pos, p.pos) < 60)

    /** Applies hidden, renamed and added places to a list of places around [center]. */
    fun applyToPlaces(list: List<Place>, center: LatLon?, radius: Double): List<Place> {
        if (items.isEmpty()) return list
        val out = ArrayList<Place>()
        for (p in list) {
            if (items.any { it.type == MapCorrection.Type.HIDE_PLACE && matches(it, p) }) continue
            val rename = items.lastOrNull { it.type == MapCorrection.Type.RENAME_PLACE && matches(it, p) }
            out.add(if (rename != null) p.copy(name = rename.newName) else p)
        }
        items.filter { it.type == MapCorrection.Type.ADD_PLACE && center != null && Geo.distance(center, it.pos) <= radius }
            .forEach { out.add(Place(it.newName, it.pos, "", "Added by you", "mine:${it.created}")) }
        return if (center != null) out.sortedBy { Geo.distance(center, it.pos) } else out
    }

    /** Places you added whose name matches a search. */
    fun searchAdded(query: String): List<Place> = items
        .filter { it.type == MapCorrection.Type.ADD_PLACE && it.newName.contains(query.trim(), ignoreCase = true) }
        .map { Place(it.newName, it.pos, "", "Added by you", "mine:${it.created}") }

    /** A street's name with any rename you made nearby. */
    fun streetName(name: String, near: LatLon): String =
        items.lastOrNull { it.type == MapCorrection.Type.RENAME_STREET && it.name.equals(name, ignoreCase = true) && Geo.distance(it.pos, near) < 2000 }
            ?.newName ?: name
}
