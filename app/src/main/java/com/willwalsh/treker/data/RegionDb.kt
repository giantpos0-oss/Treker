package com.willwalsh.treker.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A place: a business, landmark, address, favorite or search result. */
data class Place(
    val name: String,
    val pos: LatLon,
    val address: String = "",
    val kind: String = "",
    val placeId: String? = null,
) {
    fun toJson() = JSONObject().put("name", name).put("lat", pos.lat).put("lon", pos.lon)
        .put("address", address).put("kind", kind).put("placeId", placeId ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Place(
            o.getString("name"), LatLon(o.getDouble("lat"), o.getDouble("lon")),
            o.optString("address"), o.optString("kind"), o.optString("placeId").ifBlank { null },
        )
    }
}

/** Where a spot is: country down to street address, from the offline map. */
data class AreaInfo(
    val country: String?, val state: String?, val county: String?, val city: String?,
    val neighborhood: String?, val zip: String?, val street: String?, val address: String?,
)

/**
 * One downloaded region's street database (built by tools/build_region.py from OpenStreetMap).
 * Everything is looked up by ~1 km grid cells, so queries stay fast even for a whole state.
 */
class RegionDb(val file: File) {
    val db: SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    private val meta: Map<String, String> = buildMap {
        db.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }
    val id = meta["id"] ?: file.nameWithoutExtension
    val name = meta["name"] ?: id
    private val minLat = meta["minlat"]?.toDoubleOrNull() ?: -90.0
    private val minLon = meta["minlon"]?.toDoubleOrNull() ?: -180.0
    private val maxLat = meta["maxlat"]?.toDoubleOrNull() ?: 90.0
    private val maxLon = meta["maxlon"]?.toDoubleOrNull() ?: 180.0
    val boxSize get() = (maxLat - minLat) * (maxLon - minLon)

    fun covers(p: LatLon) = p.lat in minLat..maxLat && p.lon in minLon..maxLon

    fun close() = db.close()

    companion object {
        const val CELL = 0.01
        fun cellOf(lat: Double, lon: Double): Long = ((lat + 90) / CELL).toLong() * 100000 + ((lon + 180) / CELL).toLong()

        /** Every cell within [meters] of [p]. */
        fun cellsAround(p: LatLon, meters: Double): List<Long> {
            val dLat = meters / 110_540.0
            val dLon = meters / (111_320.0 * Math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
            val latLo = ((p.lat - dLat + 90) / CELL).toLong(); val latHi = ((p.lat + dLat + 90) / CELL).toLong()
            val lonLo = ((p.lon - dLon + 180) / CELL).toLong(); val lonHi = ((p.lon + dLon + 180) / CELL).toLong()
            val out = ArrayList<Long>()
            for (a in latLo..latHi) for (o in lonLo..lonHi) out.add(a * 100000 + o)
            return out
        }
    }

    private fun cellList(p: LatLon, meters: Double) = cellsAround(p, meters).joinToString(",")

    /**
     * Streets around [p]. Junction nodes keep their shared id (0 and up) so streets connect;
     * other nodes get a unique negative id.
     */
    fun streetArea(p: LatLon, radius: Double): StreetArea {
        val ways = ArrayList<Way>()
        val nodes = HashMap<Long, LatLon>()
        var synthetic = -1L
        db.rawQuery(
            "SELECT s.id, s.name, s.kind, s.geom FROM streets s WHERE s.id IN " +
                "(SELECT DISTINCT street FROM street_cells WHERE cell IN (${cellList(p, radius)}))", null,
        ).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(3)
                val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
                val ids = ArrayList<Long>(blob.size / 12)
                while (buf.remaining() >= 12) {
                    val lat = buf.int / 1e6; val lon = buf.int / 1e6; val j = buf.int
                    val id = if (j >= 0) j.toLong() else synthetic--
                    nodes[id] = LatLon(lat, lon)
                    ids.add(id)
                }
                ways.add(Way(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", ids))
            }
        }
        return StreetArea(p, radius, ways, nodes)
    }

    /** Named places within [radius] meters, nearest first. */
    fun pois(p: LatLon, radius: Double): List<Place> {
        val out = ArrayList<Pair<Place, Double>>()
        db.rawQuery("SELECT id, name, kind, lat, lon, address FROM pois WHERE cell IN (${cellList(p, radius)})", null).use { c ->
            while (c.moveToNext()) {
                val pos = LatLon(c.getDouble(3), c.getDouble(4))
                val d = Geo.distance(p, pos)
                if (d <= radius) out.add(Place(c.getString(1), pos, c.getString(5) ?: "", c.getString(2) ?: "", "osm:$id:${c.getLong(0)}") to d)
            }
        }
        return out.sortedBy { it.second }.map { it.first }
    }

    /** The nearest house address within [maxMeters]. */
    fun nearestAddress(p: LatLon, maxMeters: Double = 60.0): Triple<String, String?, String?>? {
        var best: Triple<String, String?, String?>? = null
        var bestD = maxMeters
        db.rawQuery("SELECT number, street, city, postcode, lat, lon FROM addresses WHERE cell IN (${cellList(p, maxMeters)})", null).use { c ->
            while (c.moveToNext()) {
                val d = Geo.distance(p, LatLon(c.getDouble(4), c.getDouble(5)))
                if (d < bestD) {
                    bestD = d
                    best = Triple("${c.getString(0)} ${c.getString(1)}", c.getString(2)?.ifBlank { null }, c.getString(3)?.ifBlank { null })
                }
            }
        }
        return best
    }

    /** Country, state, county, city, neighborhood and zip areas containing [p], smallest wins per level. */
    fun areasAt(p: LatLon): Map<String, String> {
        val found = HashMap<String, Pair<String, Double>>()
        db.rawQuery(
            "SELECT level, name, minlat, minlon, maxlat, maxlon, geom FROM areas WHERE minlat <= ? AND maxlat >= ? AND minlon <= ? AND maxlon >= ?",
            arrayOf(p.lat.toString(), p.lat.toString(), p.lon.toString(), p.lon.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                if (!inside(p, c.getBlob(6))) continue
                val level = c.getString(0)
                val size = (c.getDouble(4) - c.getDouble(2)) * (c.getDouble(5) - c.getDouble(3))
                val prev = found[level]
                if (prev == null || size < prev.second) found[level] = c.getString(1) to size
            }
        }
        return found.mapValues { it.value.first }
    }

    /** Even-odd point-in-polygon over every ring of the packed geometry. */
    private fun inside(p: LatLon, blob: ByteArray): Boolean {
        val b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val y = p.lat; val x = p.lon
        var inside = false
        repeat(b.int) {
            val n = b.int
            if (n == 0) return@repeat
            var py = b.int / 1e6; var px = b.int / 1e6
            for (i in 1 until n) {
                val cy = b.int / 1e6; val cx = b.int / 1e6
                if ((cy > y) != (py > y) && x < (px - cx) * (y - cy) / (py - cy) + cx) inside = !inside
                py = cy; px = cx
            }
        }
        return inside
    }

    /**
     * Search by name, address or street. "123 Main St" finds the house; "Main Street" the street;
     * anything else matches place names. Nearest to [near] first.
     */
    fun search(query: String, near: LatLon?): List<Place> {
        val q = query.trim()
        val out = ArrayList<Place>()
        val number = Regex("^(\\d+[A-Za-z]?)\\s+(.+)$").find(q)
        if (number != null) {
            val num = number.groupValues[1]
            val streetPart = number.groupValues[2].substringBefore(",").trim()
            val pattern = streetPattern(streetPart)
            db.rawQuery("SELECT number, street, city, postcode, lat, lon FROM addresses WHERE number = ? AND street LIKE ? LIMIT 50",
                arrayOf(num, pattern)).use { c ->
                while (c.moveToNext()) {
                    val line = listOfNotNull("${c.getString(0)} ${c.getString(1)}", c.getString(2)?.ifBlank { null }, c.getString(3)?.ifBlank { null }).joinToString(", ")
                    out.add(Place("${c.getString(0)} ${c.getString(1)}", LatLon(c.getDouble(4), c.getDouble(5)), line, "Address"))
                }
            }
            if (out.isEmpty()) {
                // No exact house: offer the street itself.
                streets(pattern, 10).forEach { out.add(it.copy(kind = "Street, house $num not found")) }
            }
        }
        val like = "%" + q.replace("%", "").replace("_", "\\_") + "%"
        db.rawQuery("SELECT id, name, kind, lat, lon, address FROM pois WHERE name LIKE ? ESCAPE '\\' OR kind LIKE ? ESCAPE '\\' LIMIT 400",
            arrayOf(like, like)).use { c ->
            while (c.moveToNext()) out.add(Place(c.getString(1), LatLon(c.getDouble(3), c.getDouble(4)), c.getString(5) ?: "", c.getString(2) ?: "", "osm:$id:${c.getLong(0)}"))
        }
        if (number == null) streets(streetPattern(q), 10).forEach { out.add(it) }
        val sorted = if (near != null) out.sortedBy { Geo.distance(near, it.pos) } else out
        return sorted.distinctBy { it.placeId ?: "${it.name}|${it.pos}" }.take(50)
    }

    /** "Main St" also matches "Main Street": common abbreviations become wildcards. */
    private fun streetPattern(s: String): String {
        val words = s.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.map { w ->
            when (w.trimEnd('.')) {
                "st", "street" -> "st%"
                "ave", "av", "avenue" -> "av%"
                "rd", "road" -> "r%d"
                "dr", "drive" -> "dr%"
                "blvd", "boulevard" -> "b%l%v%d"
                "ln", "lane" -> "l%n%"
                "ct", "court" -> "c%t"
                "pl", "place" -> "pl%"
                "hwy", "highway" -> "h%w%y"
                "n", "north" -> "n%"
                "s", "south" -> "s%"
                "e", "east" -> "e%"
                "w", "west" -> "w%"
                else -> w
            }
        }
        return words.joinToString(" ") + "%"
    }

    private fun streets(pattern: String, limit: Int): List<Place> {
        val out = ArrayList<Place>()
        db.rawQuery("SELECT name, geom FROM streets WHERE name LIKE ? GROUP BY name LIMIT ?", arrayOf(pattern, limit.toString())).use { c ->
            while (c.moveToNext()) {
                val b = ByteBuffer.wrap(c.getBlob(1)).order(ByteOrder.LITTLE_ENDIAN)
                val n = b.remaining() / 12
                b.position((n / 2) * 12)
                val pos = LatLon(b.int / 1e6, b.int / 1e6)
                out.add(Place(c.getString(0), pos, "", "Street"))
            }
        }
        return out
    }
}
