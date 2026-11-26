package com.willwalsh.treker.browse

import androidx.compose.runtime.mutableStateOf
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.LatLon
import com.willwalsh.treker.data.StreetArea
import com.willwalsh.treker.data.StreetMap
import com.willwalsh.treker.data.Way

/**
 * Map browsing: walk the streets virtually, one intersection at a time, like StellarTrek's
 * virtual mode. Swipe up moves forward to the next intersection, down turns around, left and
 * right turn onto the street to that side.
 */
class VirtualWalker(private val app: TrekerApp) {
    val position = mutableStateOf<LatLon?>(null)
    val description = mutableStateOf("Map browsing hasn't started.")

    private var area: StreetArea? = null
    private var node: Long? = null
    /** The street and the direction along it you're facing: the next node index step (+1 or -1). */
    private var way: Way? = null
    private var index = 0
    private var dir = 1

    private val units get() = app.settings.units
    private fun say(text: String) { description.value = text; app.speaker.say(text, interrupt = true) }

    /** Starts at the street spot nearest [start] (your current position, or a place you chose). */
    suspend fun startAt(start: LatLon, label: String? = null) {
        say("Loading streets.")
        val a = StreetMap.around(start, 900.0)
        if (a == null) { say("There's no offline map here. Download one in Help and settings, under Offline maps."); return }
        area = a
        val n = a.nearestNode(start)
        if (n == null) { say("No streets found here."); return }
        // Prefer facing the direction you're travelling or the phone points.
        val facing = app.location.fix.value?.course ?: app.compass.heading.value ?: 0.0
        placeAt(n, facing)
        val w = way ?: return
        say(buildString {
            if (label != null) append("Browsing from $label. ")
            append("On ${w.name}. ")
            append(lookAhead())
        })
    }

    private fun placeAt(n: Long, facing: Double) {
        val a = area ?: return
        node = n
        position.value = a.nodes[n]
        // Pick the named street through this node whose direction is closest to [facing].
        val options = directionsAt(n)
        val best = options.minByOrNull { Math.abs(Geo.angleDiff(facing, it.bearing)) } ?: return
        way = best.way; index = best.index; dir = best.dir
    }

    private data class Option(val way: Way, val index: Int, val dir: Int, val bearing: Double)

    /** Every way you can go from node [n]: each street through it, in both directions. */
    private fun directionsAt(n: Long): List<Option> {
        val a = area ?: return emptyList()
        val here = a.nodes[n] ?: return emptyList()
        val out = ArrayList<Option>()
        for (w in a.waysAt[n].orEmpty()) {
            if (w.name.isBlank()) continue
            w.nodes.withIndex().filter { it.value == n }.forEach { (i, _) ->
                for (d in listOf(1, -1)) {
                    val next = w.nodes.getOrNull(i + d)?.let { a.nodes[it] } ?: continue
                    out.add(Option(w, i, d, Geo.bearing(here, next)))
                }
            }
        }
        return out
    }

    private fun heading(): Double {
        val a = area ?: return 0.0
        val w = way ?: return 0.0
        val here = a.nodes[w.nodes[index]] ?: return 0.0
        val next = w.nodes.getOrNull(index + dir)?.let { a.nodes[it] } ?: return 0.0
        return Geo.bearing(here, next)
    }

    /** Walks along the current street to the next intersection or dead end. Returns the node and distance. */
    private fun walkAhead(): Triple<Long, Int, Double>? {
        val a = area ?: return null
        val w = way ?: return null
        var i = index; var dist = 0.0
        // The intersection you're standing at, so the other half of a divided road isn't a "next" one.
        val here = a.clusterOf(w.nodes[index])
        while (true) {
            val ni = i + dir
            if (ni !in w.nodes.indices) return if (i == index) null else Triple(w.nodes[i], i, dist)
            val p1 = a.nodes[w.nodes[i]]; val p2 = a.nodes[w.nodes[ni]]
            if (p1 != null && p2 != null) dist += Geo.distance(p1, p2)
            i = ni
            val n = w.nodes[i]
            if (a.intersectionAt(n) != null && (here == null || a.clusterOf(n) != here)) return Triple(n, i, dist)
            // Where this street continues as another way with the same name, keep going on it.
            if (i == 0 || i == w.nodes.size - 1) {
                val cont = a.waysAt[n].orEmpty().firstOrNull { it.id != w.id && it.name == w.name }
                if (cont != null) return Triple(n, i, dist)
            }
        }
    }

    private fun lookAhead(): String {
        val a = area ?: return ""
        val ahead = walkAhead() ?: return "Dead end ahead."
        val i = a.clusterIntersection(ahead.first)
        val what = i?.spoken(way?.name) ?: "end of ${way?.name}"
        return "Next: $what, ${Geo.distanceWords(ahead.third, units)}."
    }

    fun forward() {
        val a = area ?: return say("Map browsing hasn't started.")
        val w = way ?: return
        val ahead = walkAhead() ?: return say("Dead end. Swipe down to turn around.")
        val (n, i, dist) = ahead
        index = i; node = n
        position.value = a.nodes[n]
        // Reached the end of this piece of street: carry on along the same-named street.
        if (index + dir !in w.nodes.indices) {
            val cont = a.waysAt[n].orEmpty().firstOrNull { it.id != w.id && it.name == w.name }
            if (cont != null) {
                val ci = cont.nodes.indexOf(n)
                way = cont; index = ci; dir = if (ci == 0) 1 else -1
            }
        }
        maybeReload()
        val inter = a.clusterIntersection(n)
        val here = inter?.spoken(w.name) ?: "End of ${w.name}"
        say("$here. Moved ${Geo.distanceWords(dist, units)}. ${lookAhead()}")
    }

    fun turnAround() {
        val w = way ?: return say("Map browsing hasn't started.")
        if (index - dir in w.nodes.indices) dir = -dir
        say("Turned around on ${w.name}. ${lookAhead()}")
    }

    fun turn(left: Boolean) {
        val n = node ?: return say("Map browsing hasn't started.")
        val h = heading()
        val target = (h + (if (left) -90.0 else 90.0) + 360.0) % 360.0
        val options = directionsAt(n).filter { Math.abs(Geo.angleDiff(h, it.bearing)) > 30 }
        val best = options.filter { Math.abs(Geo.angleDiff(target, it.bearing)) < 75 }
            .minByOrNull { Math.abs(Geo.angleDiff(target, it.bearing)) }
        if (best == null) return say("No street to the ${if (left) "left" else "right"} here.")
        way = best.way; index = best.index; dir = best.dir
        say("Turned ${if (left) "left" else "right"} onto ${best.way.name}. ${lookAhead()}")
    }

    fun describe() {
        val w = way ?: return say("Map browsing hasn't started.")
        val n = node
        val inter = n?.let { area?.clusterIntersection(it) }
        say(buildString {
            if (inter != null) append("At ${inter.spoken(w.name)}. ")
            append("On ${w.name}. ")
            append(lookAhead())
        })
    }

    /** Streets are downloaded an area at a time; fetch the next area when browsing nears the edge. */
    private fun maybeReload() {
        val p = position.value ?: return
        val a = area ?: return
        if (a.covers(p, 250.0)) return
        // Reloading is a network call; flagged here and done by the screen, which owns a coroutine scope.
        needsReload = true
    }

    var needsReload = false
        private set

    suspend fun reloadAround() {
        val p = position.value ?: return
        needsReload = false
        val a = StreetMap.around(p, 900.0) ?: return
        val oldWay = way ?: return
        val n = node ?: return
        area = a
        val fresh = a.waysAt[n].orEmpty().firstOrNull { it.name == oldWay.name }
            ?: a.waysAt[n].orEmpty().firstOrNull()
        if (fresh != null) {
            val h = heading()
            way = fresh; index = fresh.nodes.indexOf(n)
            dir = if (index + 1 < fresh.nodes.size && a.nodes[fresh.nodes[index + 1]]?.let { Math.abs(Geo.angleDiff(h, Geo.bearing(a.nodes[n]!!, it))) < 90 } == true) 1 else -1
        }
    }
}
