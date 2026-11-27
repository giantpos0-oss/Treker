package com.willwalsh.treker.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext

/** A region offered for download: a street database plus routing tiles. */
data class RegionInfo(
    val id: String, val name: String, val group: String,
    val dbUrl: String, val dbSize: Long, val tilesUrl: String, val tilesSize: Long, val built: String,
    val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
) {
    val totalSize get() = dbSize + tilesSize
    fun covers(p: LatLon) = p.lat in minLat..maxLat && p.lon in minLon..maxLon
}

/**
 * The offline map catalog and downloads. Maps are built from OpenStreetMap by the Treker repo's
 * "Build offline map" workflow and published on its "maps" release.
 */
object Regions {
    const val CATALOG = "https://github.com/giantpos0-oss/Treker/releases/download/maps/regions.json"

    val catalog = mutableStateOf<List<RegionInfo>>(emptyList())
    val catalogStatus = mutableStateOf("")
    /** Download progress by region id, 0 to 1. */
    val progress = mutableStateMapOf<String, Float>()

    suspend fun loadCatalog(): List<RegionInfo> = withContext(Dispatchers.IO) {
        catalogStatus.value = "Loading the list of maps."
        try {
            val text = open(CATALOG).inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("regions")
            val list = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val db = o.getJSONObject("db"); val tiles = o.getJSONObject("tiles")
                val box = o.getJSONArray("bbox")
                RegionInfo(
                    o.getString("id"), o.getString("name"), o.optString("group"),
                    db.getString("url"), db.optLong("size"), tiles.getString("url"), tiles.optLong("size"),
                    o.optString("built"), box.getDouble(0), box.getDouble(1), box.getDouble(2), box.getDouble(3),
                )
            }.sortedWith(compareBy({ it.group }, { it.name }))
            catalog.value = list
            catalogStatus.value = if (list.isEmpty()) "No maps are published yet." else ""
            list
        } catch (e: Exception) {
            catalogStatus.value = "Couldn't load the list of maps. Check your internet connection."
            emptyList()
        }
    }

    fun dbFile(id: String) = File(Offline.mapsDir, "$id.db")
    fun tilesFile(id: String) = File(Offline.mapsDir, "$id.tar")
    fun isInstalled(id: String) = dbFile(id).exists() && tilesFile(id).exists()

    /** The build date of the installed map, like 2026-11-26, read from the map itself. */
    fun installedBuilt(id: String): String? = runCatching {
        android.database.sqlite.SQLiteDatabase.openDatabase(dbFile(id).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM meta WHERE key = 'built'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        }
    }.getOrNull()

    fun hasUpdate(r: RegionInfo): Boolean = isInstalled(r.id) && (installedBuilt(r.id) ?: "") < r.built

    /**
     * When Treker starts: any installed map with a newer build is updated by itself on Wi-Fi.
     * On mobile data, Treker only says an update is ready, since maps are large.
     */
    suspend fun updateInstalled(unmetered: Boolean, say: (String) -> Unit) {
        val newer = runCatching { loadCatalog() }.getOrDefault(emptyList()).filter { hasUpdate(it) }
        for (r in newer) {
            if (!unmetered) { say("A newer ${r.name} map is ready. Update it on Wi-Fi, or in Help and settings, Offline maps."); continue }
            say("Updating the ${r.name} map. You can keep using Treker.")
            runCatching { download(r) }
                .onSuccess { say("The ${r.name} map is updated.") }
                .onFailure { say("The ${r.name} map didn't update. Treker will try again next time it starts.") }
        }
    }

    /** Downloads and unpacks a region. Throws on failure; partial files are removed. */
    suspend fun download(r: RegionInfo) = withContext(Dispatchers.IO) {
        progress[r.id] = 0f
        val total = r.totalSize.coerceAtLeast(1)
        var done = 0L
        val tmpDb = File(Offline.mapsDir, "${r.id}.db.part")
        val tmpTiles = File(Offline.mapsDir, "${r.id}.tar.part")
        try {
            fetchGz(r.dbUrl, tmpDb) { n -> done += n; progress[r.id] = (done.toFloat() / total).coerceAtMost(0.99f) }
            fetchGz(r.tilesUrl, tmpTiles) { n -> done += n; progress[r.id] = (done.toFloat() / total).coerceAtMost(0.99f) }
            Offline.close(r.id)
            tmpDb.renameTo(dbFile(r.id))
            tmpTiles.renameTo(tilesFile(r.id))
            Offline.reload()
            StreetMap.clear()
        } finally {
            tmpDb.delete(); tmpTiles.delete()
            progress.remove(r.id)
        }
    }

    suspend fun delete(id: String) {
        Offline.close(id)
        withContext(Dispatchers.IO) { dbFile(id).delete(); tilesFile(id).delete() }
        StreetMap.clear()
    }

    /** Streams a gzip download to [dest], reporting compressed bytes read. */
    private suspend fun fetchGz(url: String, dest: File, onBytes: (Long) -> Unit) {
        val conn = open(url)
        val counting = object : java.io.FilterInputStream(conn.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len); if (n > 0) onBytes(n.toLong()); return n
            }
        }
        GZIPInputStream(counting, 1 shl 16).use { input ->
            dest.outputStream().buffered(1 shl 16).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "Treker (Android; https://github.com/giantpos0-oss/Treker)")
            }
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code !in 200..299) throw Exception("Download failed, error $code")
            return c
        }
        throw Exception("Too many redirects")
    }

    fun sizeWords(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f gigabytes".format(bytes / 1e9) else "${bytes / 1_000_000} megabytes"
}
