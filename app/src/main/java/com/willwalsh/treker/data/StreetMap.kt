package com.willwalsh.treker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A street or path from OpenStreetMap. */
data class Way(val id: Long, val name: String, val kind: String, val nodes: List<Long>)

/** A spot where two or more differently named streets meet. */
data class Intersection(val node: Long, val pos: LatLon, val streets: List<String>) {
    fun spoken(): String = when (streets.size) {
        0, 1 -> streets.firstOrNull() ?: "Unnamed intersection"
        2 -> "${streets[0]} and ${streets[1]}"
        else -> streets.dropLast(1).joinToString(", ") + " and " + streets.last()
    }
}

/**
 * Streets and intersections around a point, from OpenStreetMap (© OpenStreetMap contributors,
 * licensed under the Open Database License). Downloaded an area at a time and kept while you're in it.
 */
class StreetArea(val center: LatLon, val radius: Double, val ways: List<Way>, val nodes: Map<Long, LatLon>) {
    /** Ways that pass through each node. */
    val waysAt: Map<Long, List<Way>> = buildMap<Long, MutableList<Way>> {
        for (w in ways) for (n in w.nodes) getOrPut(n) { ArrayList() }.add(w)
    }

    val intersections: List<Intersection> = waysAt.mapNotNull { (node, ws) ->
        val names = ws.map { it.name }.filter { it.isNotBlank() }.distinct()
        val pos = nodes[node] ?: return@mapNotNull null
        if (names.size >= 2) Intersection(node, pos, names) else null
    }
    private val intersectionByNode = intersections.associateBy { it.node }
    fun intersectionAt(node: Long) = intersectionByNode[node]

    fun covers(p: LatLon, margin: Double) = Geo.distance(center, p) < radius - margin

    /** The named street nearest to [p] and how far away it is, in meters. */
    fun nearestStreet(p: LatLon): Pair<Way, Double>? {
        var best: Way? = null; var bestD = Double.MAX_VALUE
        for (w in ways) {
            if (w.name.isBlank()) continue
            for (i in 0 until w.nodes.size - 1) {
                val a = nodes[w.nodes[i]] ?: continue
                val b = nodes[w.nodes[i + 1]] ?: continue
                val d = distanceToSegment(p, a, b)
                if (d < bestD) { bestD = d; best = w }
            }
        }
        return best?.let { it to bestD }
    }

    /** The nearest node on any street to [p]: where map browsing starts. */
    fun nearestNode(p: LatLon, namedOnly: Boolean = true): Long? =
        waysAt.keys.filter { n -> !namedOnly || waysAt[n].orEmpty().any { it.name.isNotBlank() } }
            .minByOrNull { n -> nodes[n]?.let { Geo.distance(p, it) } ?: Double.MAX_VALUE }

    companion object {
        /** Distance from p to the segment a-b, in meters (flat-earth, fine at street scale). */
        fun distanceToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
            val k = Math.cos(Math.toRadians(p.lat)) * 111_320.0
            val ax = (a.lon - p.lon) * k; val ay = (a.lat - p.lat) * 110_540.0
            val bx = (b.lon - p.lon) * k; val by = (b.lat - p.lat) * 110_540.0
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + t * dx; val y = ay + t * dy
            return Math.sqrt(x * x + y * y)
        }
    }
}

/** Downloads and caches street areas from OpenStreetMap's Overpass service. */
object StreetMap {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private val lock = Mutex()
    private val cache = ArrayList<StreetArea>()
    private var lastFailure = 0L

    /** The cached area covering [p], or downloads one. Null if offline or the service is busy. */
    suspend fun around(p: LatLon, radius: Double = 600.0): StreetArea? = lock.withLock {
        cache.firstOrNull { it.covers(p, 150.0) }?.let { return it }
        if (System.currentTimeMillis() - lastFailure < 20_000) return null
        val area = runCatching { download(p, radius) }.getOrNull()
        if (area == null) { lastFailure = System.currentTimeMillis(); return null }
        cache.add(0, area)
        while (cache.size > 6) cache.removeAt(cache.size - 1)
        area
    }

    private suspend fun download(p: LatLon, radius: Double): StreetArea = withContext(Dispatchers.IO) {
        // Roads and walkable paths people use to get around, with their nodes.
        val q = """
            [out:json][timeout:25];
            way(around:${radius.toInt()},${p.lat},${p.lon})[highway]
              [highway!~"^(proposed|construction|abandoned|platform|raceway|bus_guideway|elevator)$"];
            (._;>;);
            out body;
        """.trimIndent()
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Treker/1.0 (Android GPS for blind people)")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val els = JSONObject(body).getJSONArray("elements")
        val nodes = HashMap<Long, LatLon>()
        val ways = ArrayList<Way>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            when (e.getString("type")) {
                "node" -> nodes[e.getLong("id")] = LatLon(e.getDouble("lat"), e.getDouble("lon"))
                "way" -> {
                    val tags = e.optJSONObject("tags")
                    val kind = tags?.optString("highway").orEmpty()
                    val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: tags?.optString("ref")?.takeIf { it.isNotBlank() }
                        ?: unnamedLabel(kind, tags)
                    val ns = e.getJSONArray("nodes")
                    ways.add(Way(e.getLong("id"), name, kind, List(ns.length()) { ns.getLong(it) }))
                }
            }
        }
        StreetArea(p, radius, ways, nodes)
    }

    /** Unnamed sidewalks, crossings and service roads stay in the map for routing but have no name to say. */
    private fun unnamedLabel(@Suppress("UNUSED_PARAMETER") kind: String, @Suppress("UNUSED_PARAMETER") tags: JSONObject?): String = ""
}
