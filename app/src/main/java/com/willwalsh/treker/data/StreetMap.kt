package com.willwalsh.treker.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A street or path from OpenStreetMap. */
data class Way(val id: Long, val name: String, val kind: String, val nodes: List<Long>)

/** A spot where two or more differently named streets meet. */
data class Intersection(val node: Long, val pos: LatLon, val streets: List<String>) {
    /** "Main Street and Oak Avenue": used by live exploration, What's Around and map browsing. */
    fun spoken(): String = when (streets.size) {
        0 -> "Unnamed intersection"
        1 -> streets[0]
        2 -> "${streets[0]} and ${streets[1]}"
        else -> streets.dropLast(1).joinToString(", ") + " and " + streets.last()
    }

    /** HumanWare style, for Where am I: "Main Street crossing Oak Avenue", with [from] (the street you're on) first. */
    fun crossing(from: String? = null): String {
        if (streets.isEmpty()) return "Unnamed intersection"
        val first = streets.firstOrNull { it == from } ?: streets.first()
        val others = streets.filter { it != first }
        return when (others.size) {
            0 -> first
            1 -> "$first crossing ${others[0]}"
            else -> "$first crossing " + others.dropLast(1).joinToString(", ") + " and " + others.last()
        }
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
     * BlindExplorer doesn't announce the same intersection twice.
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

    /** The next intersection along a street, how far, and how many ways it has. */
    data class Ahead(val intersection: Intersection, val distance: Double, val legs: Int)

    /**
     * The next intersection along [streetName] (or the nearest street) from [p], going the way you're
     * heading. Without a heading, the nearer of the two either way.
     */
    fun nextIntersection(p: LatLon, heading: Double?, streetName: String?): Ahead? {
        var bw: Way? = null; var bi = 0; var bd = Double.MAX_VALUE
        for (w in ways) {
            if (w.name.isBlank() || (streetName != null && w.name != streetName)) continue
            for (i in 0 until w.nodes.size - 1) {
                val a = nodes[w.nodes[i]] ?: continue
                val b = nodes[w.nodes[i + 1]] ?: continue
                val d = distanceToSegment(p, a, b)
                if (d < bd) { bd = d; bw = w; bi = i }
            }
        }
        val way = bw ?: return null
        if (bd > 60) return null
        val a = nodes[way.nodes[bi]]!!; val b = nodes[way.nodes[bi + 1]]!!
        val t = projectT(p, a, b)
        val here = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))

        fun walk(dir: Int): Pair<Intersection, Double>? {
            var w = way
            var step = dir
            var idx = if (dir > 0) bi + 1 else bi
            var prev = here
            var dist = 0.0
            for (guard in 0 until 5000) {
                val node = w.nodes[idx]
                val pos = nodes[node] ?: return null
                dist += Geo.distance(prev, pos); prev = pos
                val inter = clusterIntersection(node)
                if (inter != null && dist > 8) return inter to dist
                var next = idx + step
                if (next !in w.nodes.indices) {
                    // The street carries on as another piece with the same name.
                    val cont = waysAt[node].orEmpty().firstOrNull { it.id != w.id && it.name == w.name } ?: return null
                    val ci = cont.nodes.indexOf(node)
                    step = if (ci == 0) 1 else -1
                    w = cont
                    next = ci + step
                    if (next !in w.nodes.indices) return null
                }
                idx = next
            }
            return null
        }
        val fwd = walk(1); val back = walk(-1)
        val pick = when {
            fwd == null -> back
            back == null -> fwd
            heading != null -> {
                val bf = Geo.bearing(here, nodes[way.nodes[bi + 1]]!!)
                if (Math.abs(Geo.angleDiff(heading, bf)) <= 90) fwd else back
            }
            else -> if (fwd.second <= back.second) fwd else back
        } ?: return null
        return Ahead(pick.first, pick.second, legs(pick.first))
    }

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
     * be a little closer, so standing at a corner doesn't make BlindExplorer name the wrong street.
     */
    fun nearestStreet(p: LatLon, course: Double? = null): Pair<Way, Double>? =
        streetCandidates(p, course).firstOrNull()?.let { it.way to it.distance }

    /** A street near you: [score] is the distance with the walking-direction and path adjustments. */
    data class Candidate(val way: Way, val score: Double, val distance: Double)

    /** The best match for each differently named street near [p], best first. */
    fun streetCandidates(p: LatLon, course: Double? = null, within: Double = 80.0): List<Candidate> {
        val best = HashMap<String, Candidate>()
        for (w in ways) {
            if (w.name.isBlank()) continue
            for (i in 0 until w.nodes.size - 1) {
                val a = nodes[w.nodes[i]] ?: continue
                val b = nodes[w.nodes[i + 1]] ?: continue
                val d = distanceToSegment(p, a, b)
                if (d > within) continue
                var score = d
                // A footpath or trail only wins when you're right on it; otherwise a real street nearby
                // is almost always where you are (sidewalks and back-yard trails run beside streets).
                if (w.kind in PATH_KINDS && d > 6) score += 12.0
                if (course != null && d < 40) {
                    val diff = Math.abs(Geo.angleDiff(course, Geo.bearing(a, b)))
                    val along = minOf(diff, 180 - diff) // either direction along the street
                    if (along > 35) score += 15.0
                }
                val prev = best[w.name]
                if (prev == null || score < prev.score) best[w.name] = Candidate(w, score, d)
            }
        }
        return best.values.sortedBy { it.score }
    }


    /** The nearest node on any street to [p]: where map browsing starts. */
    fun nearestNode(p: LatLon, namedOnly: Boolean = true): Long? =
        waysAt.keys.filter { n -> !namedOnly || waysAt[n].orEmpty().any { it.name.isNotBlank() } }
            .minByOrNull { n -> nodes[n]?.let { Geo.distance(p, it) } ?: Double.MAX_VALUE }

    companion object {
        /** OpenStreetMap kinds of way that are paths rather than streets. */
        val PATH_KINDS = setOf("footway", "path", "cycleway", "bridleway", "track", "steps", "pedestrian", "corridor")

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
