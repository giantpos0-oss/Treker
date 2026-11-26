package com.willwalsh.treker.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.Units
import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.LatLon
import com.willwalsh.treker.data.Place
import com.willwalsh.treker.data.StreetArea
import com.willwalsh.treker.location.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TravelMode(val label: String) { PEDESTRIAN("Pedestrian"), MOTORIZED("Motorized"), TRANSIT("Public transit") }

/**
 * Follows you along a route and decides what to say: a heads-up before each turn, the turn itself,
 * and what comes after. Tells [onOffRoute] when you've clearly left the route.
 */
class RouteFollower(
    val route: Route,
    private val walking: Boolean,
    private val units: Units,
    private val say: (String) -> Unit,
    private val show: (String) -> Unit,
    private val onOffRoute: () -> Unit,
    private val onArrive: () -> Unit,
) {
    private val pts = route.points
    /** Cumulative distance along the route at each point. */
    private val along: DoubleArray = DoubleArray(pts.size).also { a ->
        for (i in 1 until pts.size) a[i] = a[i - 1] + Geo.distance(pts[i - 1], pts[i])
    }
    private var next = 1
    private var seg = 0
    private var warnedFar = false
    private var warnedNear = false
    private var offCount = 0
    private var arrived = false

    fun begin() {
        val first = route.maneuvers.firstOrNull() ?: return
        say(listOf(first.verbalBefore, first.verbalAfter).filter { it.isNotBlank() }.joinToString(" "))
        show(first.text)
    }

    fun onFix(f: Fix) {
        if (arrived || pts.size < 2) return
        // Where along the route are you? Look near where you were last time.
        var bestD = Double.MAX_VALUE; var bestSeg = seg
        val from = (seg - 5).coerceAtLeast(0); val to = (seg + 80).coerceAtMost(pts.size - 2)
        for (i in from..to) {
            val d = StreetArea.distanceToSegment(f.pos, pts[i], pts[i + 1])
            if (d < bestD) { bestD = d; bestSeg = i }
        }
        seg = bestSeg
        val proj = along[seg] + StreetArea.projectT(f.pos, pts[seg], pts[seg + 1]) * (along[seg + 1] - along[seg])

        val offLimit = (if (walking) 25.0 else 60.0) + f.accuracy.coerceAtMost(40f)
        if (bestD > offLimit) { if (++offCount >= 3) { offCount = 0; onOffRoute(); return } } else offCount = 0

        val total = along.last()
        // Walking, the end of the route is only reached when you're right at it; Guidance then guides the
        // last few steps to the destination itself.
        if (total - proj < (if (walking) 4.0 else 30.0) && Geo.distance(f.pos, pts.last()) < (if (walking) 10.0 else 50.0)) {
            arrived = true; onArrive(); return
        }

        val m = route.maneuvers.getOrNull(next) ?: return
        val mAt = along[m.shapeIndex.coerceIn(0, pts.size - 1)]
        val toTurn = mAt - proj
        val speed = f.speed.toDouble()
        val far = if (walking) 60.0 else maxOf(400.0, speed * 25)
        val near = if (walking) 20.0 else maxOf(100.0, speed * 9)
        val now = if (walking) 7.0 else maxOf(25.0, speed * 2.5)
        show("In ${Geo.distanceWords(toTurn.coerceAtLeast(0.0), units)}, ${m.text}")

        when {
            toTurn <= now -> {
                say(m.verbalBefore)
                advance()
            }
            toTurn <= near && !warnedNear -> {
                warnedNear = true; warnedFar = true
                say("In ${Geo.distanceWords(toTurn, units)}, ${lowerFirst(m.verbalBefore)}")
            }
            toTurn <= far && !warnedFar && toTurn > near * 1.5 -> {
                warnedFar = true
                say("In ${Geo.distanceWords(toTurn, units)}, ${lowerFirst(m.verbalBefore)}")
            }
        }
    }

    private fun advance() {
        val done = route.maneuvers.getOrNull(next)
        next++; warnedFar = false; warnedNear = false
        if (done != null && done.verbalAfter.isNotBlank() && next < route.maneuvers.size) say(done.verbalAfter)
        route.maneuvers.getOrNull(next)?.let { show(it.text) }
    }

    /** The upcoming instructions, for the steps list. */
    fun remaining(): List<String> = route.maneuvers.drop(next).map { it.text }

    private fun lowerFirst(s: String) = if (s.length > 1 && s[0].isUpperCase() && !s[1].isUpperCase()) s[0].lowercase() + s.substring(1) else s
}

/**
 * Turn-by-turn guidance, all on the phone. Walking and motorized routes come from Valhalla on the
 * offline map and are recalculated when you leave them. Public transit trips come from
 * Transitous; the walks to and from stops are guided offline, and rides announce your stops.
 */
class Guidance(private val app: TrekerApp, private val scope: CoroutineScope) {
    val active = mutableStateOf(false)
    val mode = mutableStateOf<TravelMode?>(null)
    val destination = mutableStateOf<Place?>(null)
    val current = mutableStateOf("")
    val status = mutableStateOf("")
    val steps = mutableStateListOf<String>()

    private val router by lazy { Router(app) }
    private var follower: RouteFollower? = null
    private var trip: TransitTrip? = null
    private var job: Job? = null
    private var rerouting = false
    /** Fixes guidance follows: real GPS, or made-up ones moving along the route in practice mode. */
    private val fixes = MutableStateFlow<Fix?>(null)
    private var simJob: Job? = null
    private val units get() = app.settings.units

    /** While paused, guidance keeps following you and the screen keeps updating, but nothing is said. */
    private fun say(text: String, interrupt: Boolean = false) { if (app.settings.liveExploration) app.speaker.say(text, interrupt) }
    private fun show(text: String) { current.value = text; refreshSteps() }

    private fun refreshSteps() {
        steps.clear()
        follower?.let { steps.addAll(it.remaining()) }
        trip?.let { t -> steps.clear(); steps.addAll(t.legs.drop(t.index).map { it.instruction }) }
    }

    fun start(place: Place, travel: TravelMode) {
        stop(silent = true)
        val here = app.location.fix.value
        if (here == null) { say("Your location isn't known yet. Wait for GPS and try again.", true); return }
        destination.value = place; mode.value = travel; active.value = true
        current.value = ""; steps.clear()
        status.value = "Getting a ${travel.label.lowercase()} route to ${place.name}."
        say(status.value, true)
        job = scope.launch {
            // Real GPS feeds guidance unless practice mode is moving you along the route.
            launch { app.location.fix.collect { f -> if (simJob == null && f != null) fixes.value = f } }
            try {
                if (travel == TravelMode.TRANSIT) startTransit(here.pos, place) else startRoute(here, place, travel == TravelMode.PEDESTRIAN)
            } catch (e: Exception) {
                status.value = e.message ?: "Couldn't start guidance."
                say(status.value, true)
                stop(silent = true)
                return@launch
            }
            fixes.collect { f -> if (f != null && active.value) onFix(f) }
        }
    }

    private suspend fun startRoute(here: Fix, place: Place, walking: Boolean) {
        lastApproachSaid = Double.MAX_VALUE; precisionWarned = false
        val route = router.route(here.pos, place.pos, walking, app.settings, here.course)
        follow(route, walking, onArrive = { if (walking) endOfWalkingRoute() else arrive() })
        status.value = "${Geo.distanceWords(route.lengthMeters, units)} to ${place.name}, about ${minutes(route.seconds)}."
        say(status.value)
        follower?.begin()
        if (app.settings.simulate) simulate(route, walking)
    }

    /** The route ends on the nearest street or path; the destination itself may be a few steps further. */
    private fun endOfWalkingRoute() {
        val dest = destination.value ?: return arrive()
        val f = fixes.value ?: return arrive()
        val d = Geo.distance(f.pos, dest.pos)
        if (d <= app.settings.arrivalDistance.meters) return arrive()
        follower = null
        lastApproachSaid = d; lastApproachTime = System.currentTimeMillis()
        current.value = listOf(dest.name, Geo.distanceWords(d, units), app.explorer.direction(f, dest.pos)).filter { it.isNotBlank() }.joinToString(", ")
        say("End of the route. ${current.value}.", true)
    }

    private fun follow(route: Route, walking: Boolean, onArrive: () -> Unit) {
        follower = RouteFollower(route, walking, units,
            say = { say(it) }, show = { show(it) },
            onOffRoute = { reroute(walking, onArrive) }, onArrive = onArrive)
        refreshSteps()
    }

    private fun reroute(walking: Boolean, onArrive: () -> Unit) {
        if (rerouting) return
        val target = follower?.route?.points?.lastOrNull() ?: return
        val f = fixes.value ?: return
        rerouting = true
        say("Off route. Recalculating.", true)
        scope.launch {
            try {
                val r = router.route(f.pos, target, walking, app.settings, f.course)
                follow(r, walking, onArrive)
                follower?.begin()
            } catch (e: Exception) {
                say(e.message ?: "Couldn't recalculate.", true)
            } finally { rerouting = false }
        }
    }

    private fun onFix(f: Fix) {
        follower?.onFix(f)
        if (mode.value == TravelMode.PEDESTRIAN && active.value) approach(f)
        trip?.let { onTransitFix(it, f) }
    }

    // ---- The last few steps of a walk: straight to the destination, not just the end of the street ----

    private var lastApproachSaid = Double.MAX_VALUE
    private var lastApproachTime = 0L
    private var precisionWarned = false

    /**
     * Walking within about 150 feet of the destination: Treker says how far it is and which way,
     * and only says you've arrived once you're within the arrival distance with GPS precise enough
     * to be sure.
     */
    private fun approach(f: Fix) {
        val dest = destination.value ?: return
        val d = Geo.distance(f.pos, dest.pos)
        if (d > 45 && follower != null) return
        val s = app.settings
        val precise = f.accuracy <= maxOf(s.gpsAccuracy.meters, s.arrivalDistance.meters.toFloat())
        if (d <= s.arrivalDistance.meters && precise) { arrive(); return }
        if (!precise) {
            if (!precisionWarned) {
                precisionWarned = true
                say("Within about ${Geo.distanceWords(d + f.accuracy, units)} of ${dest.name}, but GPS isn't precise enough here to say exactly where.")
            }
            return
        }
        precisionWarned = false
        val now = System.currentTimeMillis()
        // Speak each time you've closed about a fifth of the remaining distance, at most every 3 seconds.
        if (d < lastApproachSaid * 0.8 && now - lastApproachTime > 3000) {
            lastApproachSaid = d; lastApproachTime = now
            val words = listOf(dest.name, Geo.distanceWords(d, units), app.explorer.direction(f, dest.pos)).filter { it.isNotBlank() }
            current.value = words.joinToString(", ")
            say(current.value, true)
        }
    }

    private fun arrive() {
        val name = destination.value?.name ?: "your destination"
        status.value = "Arrived at $name."
        say(status.value, true)
        stop(silent = true)
    }

    // ---- Public transit ----

    private suspend fun startTransit(from: LatLon, place: Place) {
        val t = Transit.plan(from, place.pos, units) ?: throw Exception("No public transit trip found to ${place.name}.")
        if (t.legs.isEmpty()) throw Exception("No public transit trip found to ${place.name}.")
        trip = t
        status.value = "Transit trip to ${place.name}: ${t.summary}"
        say(status.value)
        startLeg(t)
    }

    private suspend fun startLeg(t: TransitTrip) {
        val leg = t.legs.getOrNull(t.index) ?: return
        follower = null
        show(leg.instruction)
        say(leg.instruction)
        if (leg.walking) {
            // Walk to the stop with offline walking guidance when the map allows; otherwise straight-line hints.
            val here = fixes.value?.pos ?: leg.from.pos
            runCatching { router.route(here, leg.to.pos, true, app.settings, fixes.value?.course) }.onSuccess { r ->
                follow(r, true, onArrive = { advanceLeg() })
                if (app.settings.simulate) simulate(r, true)
            }
        }
        refreshSteps()
    }

    private var advancing = false

    /** Moves to the next part of the trip, once, however many GPS fixes ask for it. */
    private fun advanceLeg() {
        if (advancing) return
        advancing = true
        val t = trip ?: return
        t.index++
        scope.launch {
            try { if (t.index >= t.legs.size) arrive() else startLeg(t) } finally { advancing = false }
        }
    }

    private fun onTransitFix(t: TransitTrip, f: Fix) {
        val leg = t.legs.getOrNull(t.index) ?: return
        if (leg.walking) {
            if (follower == null) {
                val d = Geo.distance(f.pos, leg.to.pos)
                current.value = listOf(leg.to.name, Geo.distanceWords(d, units), app.explorer.direction(f, leg.to.pos))
                    .filter { it.isNotBlank() }.joinToString(", ")
                if (d < 20) advanceLeg()
            }
            return
        }
        val fromStart = Geo.distance(f.pos, leg.from.pos)
        if (!leg.boarded && fromStart > 150) {
            leg.boarded = true
            say("Riding the ${leg.lineName}. I'll tell you as you pass each stop.")
        }
        if (!leg.boarded) return
        // Count the stops you've passed.
        while (leg.stopsPassed < leg.between.size && Geo.distance(f.pos, leg.between[leg.stopsPassed].pos) < 60) {
            val s = leg.between[leg.stopsPassed]
            leg.stopsPassed++
            val left = leg.between.size - leg.stopsPassed + 1
            say(if (left == 1) "${s.name}. Your stop, ${leg.to.name}, is next." else "${s.name}. $left stops to go.")
            current.value = "$left stops to ${leg.to.name}"
        }
        val toEnd = Geo.distance(f.pos, leg.to.pos)
        if (!leg.warned && toEnd < 400) { leg.warned = true; say("Get ready, ${leg.to.name} is coming up.") }
        if (toEnd < 60 && !advancing) { say("Get off now at ${leg.to.name}.", true); advanceLeg() }
    }

    // ---- Practice mode ----

    private fun simulate(route: Route, walking: Boolean) {
        simJob?.cancel()
        simJob = scope.launch {
            val speed = if (walking) 1.4 else 12.0
            val pts = route.points
            var i = 0
            var pos = pts.first()
            delay(4000)
            while (isActive && i < pts.size - 1) {
                var step = speed
                while (step > 0 && i < pts.size - 1) {
                    val d = Geo.distance(pos, pts[i + 1])
                    if (d <= step) { pos = pts[i + 1]; step -= d; i++ }
                    else { pos = Geo.move(pos, Geo.bearing(pos, pts[i + 1]), step); step = 0.0 }
                }
                val course = if (i < pts.size - 1) Geo.bearing(pos, pts[i + 1]) else null
                fixes.value = Fix(pos, 5f, speed.toFloat(), course, System.currentTimeMillis())
                delay(1000)
            }
        }
    }

    /** Repeat the current instruction. Speaks even while paused, because you asked. */
    fun repeat() {
        val text = current.value.ifBlank { status.value }
        app.speaker.say(text.ifBlank { "No guidance running." }, true)
    }

    fun stop(silent: Boolean = false) {
        val wasActive = active.value
        active.value = false
        job?.cancel(); job = null
        simJob?.cancel(); simJob = null
        follower = null; trip = null
        if (wasActive && !silent) app.speaker.say("Guidance stopped.", true)
    }

    private fun minutes(s: Double): String {
        val m = (s / 60).toInt().coerceAtLeast(1)
        return if (m >= 60) "${m / 60} hours ${m % 60} minutes" else "$m minutes"
    }
}
