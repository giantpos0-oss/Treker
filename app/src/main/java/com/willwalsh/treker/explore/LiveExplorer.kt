package com.willwalsh.treker.explore

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.willwalsh.treker.DirectionStyle
import com.willwalsh.treker.LiveItem
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.Verbosity
import com.willwalsh.treker.data.AreaInfo
import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.LatLon
import com.willwalsh.treker.data.Offline
import com.willwalsh.treker.data.Place
import com.willwalsh.treker.data.StreetMap
import com.willwalsh.treker.location.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Live exploration: as you move, announces the things you've switched on — country, state, city,
 * zip code, street, intersections you're approaching, places you pass and your direction.
 */
class LiveExplorer(private val app: TrekerApp, private val scope: CoroutineScope) {
    /** Beyond this GPS error, in meters, street, intersection, address and place announcements wait. */
    private val MAX_ACCURACY: Float get() = app.settings.gpsAccuracy.meters

    private val settings get() = app.settings

    /** What Treker last said, newest first, shown on the Explore tab. */
    val history = mutableStateListOf<String>()
    /** Where you are, for the "Where am I" button. */
    val area = mutableStateOf<AreaInfo?>(null)
    val street = mutableStateOf<String?>(null)
    /** The last intersection and place announced, shown on the Explore tab's switches. */
    val lastIntersection = mutableStateOf<String?>(null)
    val lastPlace = mutableStateOf<String?>(null)
    val status = mutableStateOf("Waiting for GPS")

    private var lastLookupAt: LatLon? = null
    private var lastLookupTime = 0L
    private var lookupJob: Job? = null
    private var announcedArea: AreaInfo? = null
    private var pendingStreet: String? = null
    private var pendingStreetCount = 0
    private val announcedIntersections = HashMap<Long, Long>()
    private val announcedPlaces = HashMap<String, Long>()
    private var streetJob: Job? = null
    private var placesJob: Job? = null
    private var lastPlacesAt: LatLon? = null
    private var noMapWarned = false
    /** Where am I has been said once since Treker started. */
    private var startSummaryDone = false

    /** Starts weak: nothing is named until GPS has proved itself precise. */
    private var gpsWeak = true
    /** Precise fixes in a row; the street is only named after several. */
    private var preciseRun = 0
    /** When GPS started looking different from what was last announced, or 0. */
    private var gpsChangeSince = 0L
    private var lastGpsSpoken = 0L

    private var lastCourse: Double? = null
    private var lastCourseTime = 0L

    fun onFix(fix: Fix) {
        fix.course?.let { lastCourse = it; lastCourseTime = System.currentTimeMillis() }
        val weak = fix.accuracy > MAX_ACCURACY
        status.value = if (weak) "GPS is weak, accurate to about ${Geo.distanceWords(fix.accuracy.toDouble(), settings.units)}"
        else "GPS good, accurate to about ${Geo.distanceWords(fix.accuracy.toDouble(), settings.units)}"
        // GPS accuracy wobbles around any cutoff. Only a change that lasts counts: weak for 20 seconds,
        // or good (with a margin) for 15 seconds. Then it's said at most once every 3 minutes.
        val now = System.currentTimeMillis()
        // Low-power fixes while you're still are rough by design: they don't change anything.
        if (app.location.lowPower) {
            status.value = "Saving battery while you're still. GPS will be precise again as soon as you move."
            return
        }
        preciseRun = if (fix.accuracy <= MAX_ACCURACY) preciseRun + 1 else 0
        // At start-up, the first 5 precise fixes in a row are enough; after that, changes must last.
        if (gpsWeak && lastGpsSpoken == 0L && gpsChangeSince == 0L && preciseRun >= 5) { gpsWeak = false; preciseRun = 5 }
        val looksChanged = if (gpsWeak) fix.accuracy <= MAX_ACCURACY * 0.8f else weak
        if (!looksChanged) gpsChangeSince = 0L
        else {
            if (gpsChangeSince == 0L) gpsChangeSince = now
            if (now - gpsChangeSince > (if (gpsWeak) 15_000 else 20_000)) {
                gpsWeak = !gpsWeak
                gpsChangeSince = 0L
                if (settings.liveExploration && settings.announceGps && now - lastGpsSpoken > 180_000) {
                    lastGpsSpoken = now
                    say(if (gpsWeak) "GPS signal weak. Street and place announcements paused." else "GPS signal good again.")
                }
            }
        }
        if (!settings.liveExploration) return
        if (!Offline.hasMapFor(fix.pos)) {
            if (!noMapWarned) {
                noMapWarned = true
                say("There's no offline map for where you are. Download one in Help and settings, under Offline maps.")
            }
            return
        }
        noMapWarned = false
        checkArea(fix)
        checkStreets(fix)
        checkPlaces(fix)
    }

    private fun say(text: String) {
        history.add(0, text)
        while (history.size > 50) history.removeAt(history.size - 1)
        app.speaker.say(text)
    }

    // Country, state, county, city, neighborhood, zip and address, from the offline map.
    private fun checkArea(fix: Fix) {
        val now = System.currentTimeMillis()
        val moved = lastLookupAt?.let { Geo.distance(it, fix.pos) } ?: Double.MAX_VALUE
        if (lookupJob?.isActive == true) return
        // Until the start-up summary has been said, keep trying every few seconds.
        if (moved < 30 && now - lastLookupTime < 120_000 && startSummaryDone) return
        if (now - lastLookupTime < 4_000) return
        lastLookupAt = fix.pos; lastLookupTime = now
        lookupJob = scope.launch {
            val info = runCatching { Offline.areaInfo(fix.pos, StreetMap.around(fix.pos), fix.course) }.getOrNull() ?: return@launch
            area.value = info
            // When Treker starts, say where you are once, whatever the switches say; the switches then
            // decide what's announced as things change.
            if (!startSummaryDone && fix.accuracy <= 30f) {
                startSummaryDone = true
                announcedArea = info
                if (info.street != null && street.value == null) street.value = info.street
                say(whereAmI())
                return@launch
            }
            val old = announcedArea
            announcedArea = info
            val parts = ArrayList<String>()
            val v = settings.areaVerbosity
            fun changed(item: LiveItem, new: String?, prev: String?, detailed: String) {
                if (on(item) && !new.isNullOrBlank() && new != prev) parts.add(if (v == Verbosity.DETAILED) detailed else new)
            }
            changed(LiveItem.COUNTRY, info.country, old?.country, "Entering ${info.country}")
            changed(LiveItem.STATE, info.state, old?.state, "Entering the state of ${info.state}")
            changed(LiveItem.COUNTY, info.county, old?.county, "Entering ${info.county}")
            changed(LiveItem.CITY, info.city, old?.city, "Entering ${info.city}")
            changed(LiveItem.NEIGHBORHOOD, info.neighborhood, old?.neighborhood, "Neighborhood: ${info.neighborhood}")
            if (on(LiveItem.ZIP) && !info.zip.isNullOrBlank() && info.zip != old?.zip)
                parts.add(if (v == Verbosity.BRIEF) spellZip(info.zip) else "zip code ${spellZip(info.zip)}")
            if (on(LiveItem.ADDRESS) && !gpsWeak && !info.address.isNullOrBlank() && info.address != old?.address)
                parts.add(addressWords(info, v == Verbosity.BRIEF))
            if (parts.isNotEmpty()) say(parts.joinToString(", "))

        }
    }

    private fun spellZip(z: String) = z.toCharArray().joinToString(" ")

    // Street names and intersections, from OpenStreetMap.
    private var lastStreetCheckAt: LatLon? = null
    private var lastStreetCheckTime = 0L

    /**
     * Which street you're on, judged fairly for how good GPS is right now. With a precise fix, the
     * nearest street. With a rougher fix (up to about 100 feet), only when the nearest street is clearly
     * closer than any other, by at least half the GPS error. Otherwise Treker says nothing.
     */
    private fun confidentStreet(map: com.willwalsh.treker.data.StreetArea, fix: Fix): com.willwalsh.treker.data.StreetArea.Candidate? {
        val acc = fix.accuracy.toDouble()
        if (acc > 30) return null
        val c = map.streetCandidates(fix.pos, fix.course)
        val best = c.firstOrNull() ?: return null
        val second = c.getOrNull(1)
        if (acc <= MAX_ACCURACY) return best.takeIf { it.distance < 20 + acc / 2 }
        val margin = (second?.score ?: Double.MAX_VALUE) - best.score
        return best.takeIf { it.distance < 25 + acc / 2 && margin >= acc * 0.5 }
    }

    private fun checkStreets(fix: Fix) {
        if (streetJob?.isActive == true) return
        // Looking up streets costs battery: only when you've moved a few meters, or every 5 seconds.
        val now = System.currentTimeMillis()
        val movedSince = lastStreetCheckAt?.let { Geo.distance(it, fix.pos) } ?: Double.MAX_VALUE
        if (movedSince < 3 && now - lastStreetCheckTime < 5_000) return
        lastStreetCheckAt = fix.pos; lastStreetCheckTime = now
        if (!on(LiveItem.STREET) && !on(LiveItem.INTERSECTIONS)) return
        streetJob = scope.launch {
            val map = StreetMap.around(fix.pos) ?: return@launch
            // With a weak GPS signal, Treker stays quiet about streets and intersections rather
            // than risk naming the wrong one.
            if (on(LiveItem.STREET)) {
                val near = confidentStreet(map, fix)
                val name = near?.way?.name
                if (near != null && name != null && name != street.value) {
                    // Only announce a new street once it's confirmed by 3 precise fixes in a row, so crossing
                    // a street, or one stray fix, isn't "on".
                    if (name == pendingStreet) pendingStreetCount++ else { pendingStreet = name; pendingStreetCount = 1 }
                    if (pendingStreetCount >= 3) {
                        street.value = name
                        say(when (settings.streetVerbosity) {
                            Verbosity.BRIEF -> name
                            Verbosity.NORMAL -> "On $name"
                            Verbosity.DETAILED -> "On $name" + (near.way.kind.let { streetKind(it) }?.let { ", $it" } ?: "")
                        })
                    }
                }
            }
            // Intersections ahead need a precise fix: their distance matters.
            if (on(LiveItem.INTERSECTIONS) && !gpsWeak && fix.accuracy <= MAX_ACCURACY) {
                val course = fix.course ?: return@launch
                val now = System.currentTimeMillis()
                val ahead = map.clusters
                    .map { it to Geo.distance(fix.pos, it.pos) }
                    .filter { (i, d) -> d in 8.0..settings.intersectionDistance.meters && Math.abs(Geo.angleDiff(course, Geo.bearing(fix.pos, i.pos))) < 30 }
                    .minByOrNull { it.second }
                if (ahead != null && now - (announcedIntersections[ahead.first.node] ?: 0L) > 180_000) {
                    announcedIntersections[ahead.first.node] = now
                    val (i, d) = ahead
                    val dist = Geo.distanceWords(d, settings.units)
                    lastIntersection.value = "${i.spoken()}, $dist"
                    say(when (settings.intersectionVerbosity) {
                        Verbosity.BRIEF -> i.spoken()
                        Verbosity.NORMAL -> "Approaching ${i.spoken()}, $dist"
                        Verbosity.DETAILED -> {
                            val legs = map.legs(i)
                            val kind = when { legs == 3 -> "T intersection"; legs >= 4 -> "$legs-way intersection"; else -> "intersection" }
                            words("Approaching $kind", i.spoken(), dist, direction(fix, i.pos))
                        }
                    })
                }
            }
        }
    }

    /** Whether [item] is switched on, using the route guidance switches while guidance is running. */
    private fun on(item: LiveItem) = settings.isOn(item, app.guidance.active.value)

    /** "near 1303 Emily Morgan Circle", or "about 1303 ..." when the number is worked out from the street's ranges. */
    private fun addressWords(a: AreaInfo, brief: Boolean): String {
        val addr = a.address ?: return ""
        return when {
            a.addressEstimated -> "about $addr"
            brief -> addr
            else -> "near $addr"
        }
    }

    /** Joins the parts of an announcement with commas, skipping any that are empty. */
    private fun words(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(", ")

    /** What kind of street it is, in plain words, for detailed street announcements. */
    private fun streetKind(highway: String): String? = when (highway) {
        "motorway", "trunk" -> "highway"
        "primary", "secondary" -> "major road"
        "tertiary" -> "through street"
        "residential", "living_street", "unclassified" -> "residential street"
        "service" -> "service road"
        "pedestrian" -> "pedestrian street"
        "footway", "path" -> "footpath"
        "cycleway" -> "bike path"
        "steps" -> "stairs"
        else -> null
    }

    // Places you pass: businesses and landmarks close by, from the offline map.
    private fun checkPlaces(fix: Fix) {
        if (!on(LiveItem.PLACES) || placesJob?.isActive == true || gpsWeak || fix.accuracy > MAX_ACCURACY) return
        val last = lastPlacesAt
        if (last != null && Geo.distance(last, fix.pos) < 10) return
        lastPlacesAt = fix.pos
        placesJob = scope.launch {
            val places = try { Offline.pois(fix.pos, 60.0) } catch (e: Exception) { return@launch }
            val now = System.currentTimeMillis()
            val close = places.map { it to Geo.distance(fix.pos, it.pos) }.filter { it.second < 25 }.sortedBy { it.second }
            for ((p, d) in close.take(2)) {
                val key = p.placeId ?: p.name
                if (now - (announcedPlaces[key] ?: 0L) < 15 * 60_000) continue
                announcedPlaces[key] = now
                val dist = Geo.distanceWords(d, settings.units)
                lastPlace.value = words(p.name, dist)
                say(when (settings.placeVerbosity) {
                    Verbosity.BRIEF -> p.name
                    Verbosity.NORMAL -> words(p.name, direction(fix, p.pos), dist)
                    Verbosity.DETAILED -> words(p.name, p.kind, direction(fix, p.pos), dist)
                })
            }
        }
    }

    /** Direction to [target] in the chosen style. Clock directions need a direction of travel or the compass. */
    fun direction(fix: Fix?, target: LatLon, facing: Double? = null): String {
        val from = fix?.pos ?: return ""
        val b = Geo.bearing(from, target)
        // The way you're walking; else the way the phone points (What's Around on); else the way you last walked.
        val heading = facing ?: fix.course ?: app.compass.heading.value
            ?: lastCourse?.takeIf { System.currentTimeMillis() - lastCourseTime < 120_000 }
        // Without a direction of travel or the compass, there's no honest left or right to give.
        heading ?: return ""
        return when (settings.directionStyle) {
            DirectionStyle.CLOCK -> Geo.clockWord(heading, b)
            DirectionStyle.RELATIVE -> Geo.relativeWord(heading, b)
        }
    }

    /**
     * Where am I, in HumanWare's style:
     * "Heading north. 1303 Emily Morgan Circle. Next intersection in 250 feet, 3-way, Emily Morgan Circle
     * crossing Southern Place. Richmond, Fort Bend County, Texas, zip code 7 7 4 0 6."
     */
    suspend fun whereAmI(): String {
        val fix = app.location.fix.value ?: return "No GPS position yet."
        val parts = ArrayList<String>()
        // Heading: the way you're walking, else the way the phone points (read briefly), else the way you last walked.
        val heading = fix.course ?: app.compass.heading.value ?: run {
            if (!app.compass.available) null else {
                app.compass.start()
                kotlinx.coroutines.delay(700)
                app.compass.heading.value.also { app.compass.stop() }
            }
        } ?: lastCourse?.takeIf { System.currentTimeMillis() - lastCourseTime < 120_000 }
        heading?.let { parts.add("Heading ${Geo.compassWord(it)}.") }

        val map = StreetMap.around(fix.pos)
        val a = area.value ?: runCatching { Offline.areaInfo(fix.pos, map, fix.course) }.getOrNull()
        val streetName = street.value ?: a?.street
        when {
            a?.address != null -> parts.add((if (a.addressEstimated) "About " else "") + a.address + ".")
            streetName != null -> parts.add("On $streetName.")
        }
        map?.nextIntersection(fix.pos, heading, streetName)?.let { n ->
            val ways = if (n.legs >= 3) "${n.legs}-way, " else ""
            parts.add("${if (heading != null) "Next" else "Nearest"} intersection in ${Geo.distanceWords(n.distance, settings.units)}, " +
                "$ways${n.intersection.crossing(streetName)}.")
        }
        val place = listOfNotNull(a?.neighborhood, a?.city, a?.county, a?.state).joinToString(", ")
        val zip = a?.zip?.let { "zip code ${spellZip(it)}" }
        listOf(place, zip).filter { !it.isNullOrBlank() }.joinToString(", ").takeIf { it.isNotBlank() }?.let { parts.add("$it.") }
        if (fix.accuracy > MAX_ACCURACY) parts.add("GPS is only accurate to about ${Geo.distanceWords(fix.accuracy.toDouble(), settings.units)}.")
        return if (parts.isEmpty()) "Looking up where you are." else parts.joinToString(" ")
    }

    /** The nearest intersection, for the "Nearest intersection" button. */
    suspend fun nearestIntersection(): String {
        val fix = app.location.fix.value ?: return "No GPS position yet."
        val map = StreetMap.around(fix.pos) ?: return "There's no offline map for where you are."
        val i = map.clusters.minByOrNull { Geo.distance(fix.pos, it.pos) } ?: return "No intersections nearby."
        return words(i.spoken(), direction(fix, i.pos), Geo.distanceWords(Geo.distance(fix.pos, i.pos), settings.units))
    }

    /** Places around you, nearest first, for the list on the Explore tab. */
    suspend fun nearbyPlaces(): List<Pair<Place, Double>> {
        val fix = app.location.fix.value ?: return emptyList()
        return Offline.pois(fix.pos, settings.placesRadius.toDouble().coerceAtLeast(100.0))
            .map { it to Geo.distance(fix.pos, it.pos) }
    }
}
