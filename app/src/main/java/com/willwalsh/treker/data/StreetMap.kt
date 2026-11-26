package com.willwalsh.treker.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Streets and intersections around a point, from the offline OpenStreetMap data
 * (© OpenStreetMap contributors, licensed under the Open Database License).
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

    /**
     * Intersections as a person would count them. Where a divided road crosses another street,
     * the map has two or more junction points a few meters apart; they're merged into one so
     * Treker doesn't announce the same intersection twice.
     */
    val clusters: List<Intersection>
    private val clusterOfNode = HashMap<Long, Int>()
    init {
        val groups = ArrayList<MutableList<Intersection>>()
        for (i in intersections) {
            val g = groups.indexOfFirst { grp ->
                grp.any { o -> Geo.distance(o.pos, i.pos) < 35 && o.streets.any { it in i.streets } }
            }
            if (g >= 0) { groups[g].add(i); clusterOfNode[i.node] = g }
            else { groups.add(mutableListOf(i)); clusterOfNode[i.node] = groups.size - 1 }
        }
        clusters = groups.mapIndexed { idx, grp ->
            val lat = grp.sumOf { it.pos.lat } / grp.size
            val lon = grp.sumOf { it.pos.lon } / grp.size
            Intersection(grp.first().node, LatLon(lat, lon), grp.flatMap { it.streets }.distinct())
        }
    }
    fun clusterOf(node: Long): Int? = clusterOfNode[node]

    /** How many ways lead out of an intersection: 3 for a T, 4 for a crossroads. */
    fun legs(i: Intersection): Int {
        val idx = clusterOfNode[i.node] ?: return 0
        val members = clusterOfNode.filterValues { it == idx }.keys
        val dirs = HashSet<Int>()
        for (n in members) for (w in waysAt[n].orEmpty()) {
            if (w.name.isBlank()) continue
            w.nodes.withIndex().filter { it.value == n }.forEach { (k, _) ->
                for (d in listOf(-1, 1)) {
                    val other = w.nodes.getOrNull(k + d) ?: continue
                    if (other in members) continue
                    val pos = nodes[other] ?: continue
                    dirs.add(((Geo.bearing(i.pos, pos) + 22.5) / 45.0).toInt() % 8)
                }
            }
        }
        return dirs.size
    }
    fun clusterIntersection(node: Long): Intersection? = clusterOfNode[node]?.let { clusters[it] }

    fun covers(p: LatLon, margin: Double) = Geo.distance(center, p) < radius - margin

    /**
     * The named street you're most likely on, and how far away it is, in meters. When you're
     * moving, a street running the same way you're going wins over a cross street that happens to
     * be a little closer, so standing at a corner doesn't make Treker name the wrong street.
     */
    fun nearestStreet(p: LatLon, course: Double? = null): Pair<Way, Double>? {
        var best: Way? = null; var bestScore = Double.MAX_VALUE; var bestD = 0.0
        for (w in ways) {
            if (w.name.isBlank()) continue
            for (i in 0 until w.nodes.size - 1) {
                val a = nodes[w.nodes[i]] ?: continue
                val b = nodes[w.nodes[i + 1]] ?: continue
                val d = distanceToSegment(p, a, b)
                var score = d
                if (course != null && d < 40) {
                    val diff = Math.abs(Geo.angleDiff(course, Geo.bearing(a, b)))
                    val along = minOf(diff, 180 - diff) // either direction along the street
                    if (along > 35) score += 15.0
                }
                if (score < bestScore) { bestScore = score; bestD = d; best = w }
            }
        }
        return best?.let { it to bestD }
    }

    /** The nearest node on any street to [p]: where map browsing starts. */
    fun nearestNode(p: LatLon, namedOnly: Boolean = true): Long? =
        waysAt.keys.filter { n -> !namedOnly || waysAt[n].orEmpty().any { it.name.isNotBlank() } }
            .minByOrNull { n -> nodes[n]?.let { Geo.distance(p, it) } ?: Double.MAX_VALUE }

    companion object {
        /** How far along the segment a-b the point nearest [p] is, from 0 (at a) to 1 (at b). */
        fun projectT(p: LatLon, a: LatLon, b: LatLon): Double {
            val k = Math.cos(Math.toRadians(p.lat)) * 111_320.0
            val ax = (a.lon - p.lon) * k; val ay = (a.lat - p.lat) * 110_540.0
            val bx = (b.lon - p.lon) * k; val by = (b.lat - p.lat) * 110_540.0
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            return if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        }

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

/** Street areas from the offline map, kept while you're in them. */
object StreetMap {
    private val lock = Mutex()
    private val cache = ArrayList<StreetArea>()

    /** The cached area covering [p], or loads one. Null when no downloaded map covers [p]. */
    suspend fun around(p: LatLon, radius: Double = 600.0): StreetArea? = lock.withLock {
        cache.firstOrNull { it.covers(p, 150.0) && it.radius >= radius - 1 }?.let { return it }
        val area = Offline.streets(p, radius) ?: return null
        cache.add(0, area)
        while (cache.size > 6) cache.removeAt(cache.size - 1)
        area
    }

    /** Forget cached streets, after maps are added or removed. */
    suspend fun clear() = lock.withLock { cache.clear() }
}
