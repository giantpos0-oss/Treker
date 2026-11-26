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
 * Follows you along a route and decides what to say, the way HumanWare's GPS does:
 * - in a vehicle: "In 2 miles, turn left onto Main Street." and again at 1 mile; at 0.2 miles
 *   "Get ready to turn left onto Main Street."; at the turn "Turn left onto Main Street.";
 * - walking: "In 200 feet, ..." then "Get ready to ..." at about 65 feet, then the instruction at the turn;
 * - once the turn has actually been made: "Continue for 2.3 miles. Then, turn right onto Oak Avenue."
 * Tells [onOffRoute] when you've clearly left the route or are going the wrong way along it.
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
    private val total = if (along.isEmpty()) 0.0 else along.last()
    private var next = 1
    private var seg = 0
    private var proj = 0.0
    private var offCount = 0
    private var wrongWayCount = 0
    private var arrived = false
    /** The instruction for the next maneuver has been said at the turn; waiting for you to make it. */
    private var turnSaid = false
    private var readySaid = false
    private val headsUpSaid = HashSet<Double>()
    /** How far the next maneuver was when the previous one was completed: heads-ups longer than this are skipped. */
    private var legLength = 0.0

    /** Live numbers for the Active route screen. */
    var remainingMeters = total
        private set
    var toNextMeters = 0.0
        private set

    private val metric = units == Units.METERS
    /** Heads-up distances: 2 miles and 1 mile driving (3 and 1.5 km), 200 feet walking (60 m). */
    private val headsUps: List<Double> = when {
        walking -> listOf(60.0)
        metric -> listOf(3000.0, 1500.0)
        else -> listOf(3218.7, 1609.3)
    }
    /** "Get ready to ..." distance: 0.2 miles driving (300 m), about 65 feet walking (20 m). */
    private val getReady = when { walking -> 20.0; metric -> 300.0; else -> 321.9 }

    init { legLength = maneuverAt(1) }

    private fun maneuverAt(i: Int): Double = route.maneuvers.getOrNull(i)?.let { along[it.shapeIndex.coerceIn(0, pts.size - 1)] } ?: total

    private fun isDestination(m: Maneuver) = m.type in 4..6

    /** The instruction in words, as said at the turn. */
    private fun words(m: Maneuver): String = if (isDestination(m)) "Arrive at your destination." else m.text.trim().let { if (it.endsWith(".")) it else "$it." }

    /** The first instruction, said when guidance starts. */
    fun firstInstruction(): String = route.maneuvers.firstOrNull()?.let { words(it) } ?: ""

    /** "In 0.3 miles, turn left onto Main Street." for Where am I and Repeat instruction. */
    fun nextInstructionWords(): String {
        val m = route.maneuvers.getOrNull(next) ?: return ""
        return "In ${Geo.distanceWords(toNextMeters, units)}, ${lowerFirst(words(m))}"
    }

    /** Every instruction still to come, with how far away it is, for Step-by-step directions. */
    fun remainingSteps(): List<String> = route.maneuvers.withIndex().drop(next).map { (i, m) ->
        "${Geo.distanceWords((maneuverAt(i) - proj).coerceAtLeast(0.0), units)}: ${words(m)}"
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
        proj = along[seg] + StreetArea.projectT(f.pos, pts[seg], pts[seg + 1]) * (along[seg + 1] - along[seg])
        remainingMeters = (total - proj).coerceAtLeast(0.0)

        // Off the route: clearly away from it for a few fixes in a row.
        val offLimit = (if (walking) 20.0 else 35.0) + f.accuracy.coerceAtMost(if (walking) 20f else 25f)
        if (bestD > offLimit) { if (++offCount >= (if (walking) 3 else 2)) { offCount = 0; onOffRoute(); return } } else offCount = 0
        // Going the wrong way along the route, like a missed turn followed by turning around.
        val course = f.course
        if (course != null && f.speed > (if (walking) 0.8f else 3f)) {
            val segBearing = Geo.bearing(pts[seg], pts[seg + 1])
            if (Math.abs(Geo.angleDiff(course, segBearing)) > 120) {
                if (++wrongWayCount >= (if (walking) 6 else 3)) { wrongWayCount = 0; onOffRoute(); return }
            } else wrongWayCount = 0
        }

        // Walking, the end of the route is only reached when you're right at it; Guidance then guides the
        // last few steps to the destination itself.
        if (remainingMeters < (if (walking) 4.0 else 30.0) && Geo.distance(f.pos, pts.last()) < (if (walking) 10.0 else 50.0)) {
            arrived = true; onArrive(); return
        }

        val m = route.maneuvers.getOrNull(next) ?: return
        val mAt = maneuverAt(next)
        val toTurn = mAt - proj
        toNextMeters = toTurn.coerceAtLeast(0.0)

        if (turnSaid) {
            // The instruction was given at the turn; once you've made it, say what comes next.
            if (proj > mAt + (if (walking) 6.0 else 25.0)) completeManeuver()
            return
        }
        show(nextInstructionWords())
        val speed = f.speed.toDouble()
        val now = if (walking) 6.0 else maxOf(30.0, speed * 3)
        when {
            isDestination(m) -> {
                // Arrival is handled above; give the heads-ups and get-ready only.
                if (toTurn <= getReady && !readySaid && legLength > getReady * 1.3) { readySaid = true; say("Get ready to arrive at your destination.") }
                else headsUp(toTurn, m)
            }
            toTurn <= now -> { turnSaid = true; say(words(m)) }
            toTurn <= getReady && !readySaid && legLength > getReady * 1.3 -> {
                readySaid = true
                say("Get ready to ${lowerFirst(words(m))}")
            }
            else -> headsUp(toTurn, m)
        }
    }

    private fun headsUp(toTurn: Double, m: Maneuver) {
        if (toTurn <= getReady * 1.5) return
        // The nearest heads-up distance you've just come within, if it fits in this stretch of the route.
        val due = headsUps.filter { it !in headsUpSaid && toTurn <= it && legLength > it * 1.15 }
        if (due.isEmpty()) return
        headsUpSaid.addAll(headsUps.filter { it >= toTurn })
        say("In ${Geo.distanceWords(due.minOrNull()!!, units)}, ${lowerFirst(words(m))}")
    }

    private fun completeManeuver() {
        next++
        turnSaid = false; readySaid = false; headsUpSaid.clear()
        val nm = route.maneuvers.getOrNull(next) ?: return
        val d = (maneuverAt(next) - proj).coerceAtLeast(0.0)
        legLength = d
        toNextMeters = d
        say("Continue for ${Geo.distanceWords(d, units)}. Then, ${lowerFirst(words(nm))}")
        show(nextInstructionWords())
    }

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
    /** Live distances for the Active route screen, updated with every GPS fix. */
    val remaining = mutableStateOf(0.0)
    val traveled = mutableStateOf(0.0)
    private var lastTraveledAt: LatLon? = null

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
        val list = follower?.remainingSteps() ?: trip?.let { t -> t.legs.drop(t.index).map { it.instruction } } ?: emptyList()
        if (list != steps.toList()) { steps.clear(); steps.addAll(list) }
    }

    fun start(place: Place, travel: TravelMode) {
        stop(silent = true)
        app.location.setLowPower(false)
        val here = app.location.fix.value
        if (here == null) { say("Your location isn't known yet. Wait for GPS and try again.", true); return }
        destination.value = place; mode.value = travel; active.value = true
        current.value = ""; steps.clear()
        traveled.value = 0.0; remaining.value = 0.0; lastTraveledAt = null
        status.value = "Getting a ${travel.label.lowercase()} route to ${place.name}."
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
        remaining.value = route.lengthMeters
        status.value = "Route length, ${Geo.distanceWords(route.lengthMeters, units)}, about ${minutes(route.seconds)}."
        // HumanWare style: "Motorized guidance. To. Corner Cafe. Route length, 4.3 miles. Head north on Main Street."
        say("${if (walking) "Pedestrian" else "Motorized"} guidance. To. ${place.name}. Route length, ${Geo.distanceWords(route.lengthMeters, units)}. " +
            (follower?.firstInstruction() ?: ""), true)
        follower?.let { current.value = it.firstInstruction() }
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
        lastWalking = walking; lastOnArrive = onArrive
        follower = RouteFollower(route, walking, units,
            say = { say(it) }, show = { show(it) },
            onOffRoute = { reroute(walking, onArrive) }, onArrive = onArrive)
        refreshSteps()
    }

    private var lastWalking = true
    private var lastOnArrive: () -> Unit = {}

    /**
     * A new route from where you are, continuing the way you're going (like mainstream GPS apps), so it
     * doesn't tell you to make U-turns to get back to the old route. [manual] is the Recalculate route button.
     */
    private fun reroute(walking: Boolean, onArrive: () -> Unit, manual: Boolean = false) {
        if (rerouting) return
        val target = destination.value?.pos ?: follower?.route?.points?.lastOrNull() ?: return
        val f = fixes.value ?: return
        rerouting = true
        say(if (manual) "Recalculating." else "Off route. Recalculating.", true)
        status.value = "Recalculating."
        scope.launch {
            try {
                // Only trust the direction of travel when actually moving.
                val heading = f.course?.takeIf { f.speed > (if (walking) 0.8f else 2.5f) }
                val r = router.route(f.pos, target, walking, app.settings, heading)
                follow(r, walking, onArrive)
                remaining.value = r.lengthMeters
                status.value = "Route length, ${Geo.distanceWords(r.lengthMeters, units)}, about ${minutes(r.seconds)}."
                val first = follower?.firstInstruction().orEmpty()
                current.value = first
                say("New route. Route length, ${Geo.distanceWords(r.lengthMeters, units)}. $first")
            } catch (e: Exception) {
                status.value = e.message ?: "Couldn't recalculate."
                say(status.value, true)
            } finally { rerouting = false }
        }
    }

    /** The Recalculate route button. */
    fun recalculate() {
        if (!active.value) { app.speaker.say("No guidance running.", true); return }
        if (trip != null && follower == null) { app.speaker.say("Recalculating isn't available while riding transit.", true); return }
        reroute(lastWalking, lastOnArrive, manual = true)
    }

    private fun onFix(f: Fix) {
        // Distance traveled, from reasonably precise fixes only.
        if (f.accuracy <= 30f) {
            lastTraveledAt?.let { val d = Geo.distance(it, f.pos); if (d in 1.0..300.0) traveled.value += d }
            lastTraveledAt = f.pos
        }
        follower?.onFix(f)
        follower?.let { remaining.value = it.remainingMeters }
        if (follower == null) destination.value?.let { remaining.value = Geo.distance(f.pos, it.pos) }
        refreshSteps()
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
        say("Public transit guidance. To. ${place.name}. ${t.summary}", true)
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
                current.value = follower?.firstInstruction().orEmpty()
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
        val text = follower?.nextInstructionWords()?.ifBlank { null } ?: current.value.ifBlank { status.value }
        app.speaker.say(text.ifBlank { "No guidance running." }, true)
    }

    /**
     * Where am I during guidance, HumanWare style:
     * "On route. To. Corner Cafe. Traveled: 1.2 miles. Remaining: 3.4 miles. Next intersection in 250 feet.
     * 3 ways, Main Street crossing Oak Avenue. In 0.3 miles, turn left onto Oak Avenue."
     */
    suspend fun whereAmIOnRoute(): String {
        val parts = ArrayList<String>()
        parts.add("On route.")
        destination.value?.let { parts.add("To. ${it.name}.") }
        parts.add("Traveled: ${Geo.distanceWords(traveled.value, units)}.")
        parts.add("Remaining: ${Geo.distanceWords(remaining.value, units)}.")
        val f = app.location.fix.value
        if (f != null) app.explorer.nextIntersectionWords(f)?.let { parts.add(it) }
        val nextWords = follower?.nextInstructionWords()?.ifBlank { null } ?: current.value.ifBlank { null }
        nextWords?.let { parts.add(it.trim().let { w -> if (w.endsWith(".")) w else "$w." }) }
        return parts.joinToString(" ")
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
