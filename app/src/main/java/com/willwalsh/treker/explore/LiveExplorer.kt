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

    private var gpsWeak = false

    private var lastCourse: Double? = null
    private var lastCourseTime = 0L

    fun onFix(fix: Fix) {
        fix.course?.let { lastCourse = it; lastCourseTime = System.currentTimeMillis() }
        val weak = fix.accuracy > MAX_ACCURACY
        status.value = if (weak) "GPS is weak, accurate to about ${Geo.distanceWords(fix.accuracy.toDouble(), settings.units)}"
        else "GPS good, accurate to about ${Geo.distanceWords(fix.accuracy.toDouble(), settings.units)}"
        if (settings.liveExploration && weak != gpsWeak) {
            gpsWeak = weak
            say(if (weak) "GPS signal weak. Street and place announcements paused." else "GPS signal good again.")
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
        if (moved < 30 && now - lastLookupTime < 120_000) return
        if (now - lastLookupTime < 4_000) return
        lastLookupAt = fix.pos; lastLookupTime = now
        lookupJob = scope.launch {
            val info = runCatching { Offline.areaInfo(fix.pos, StreetMap.around(fix.pos), fix.course) }.getOrNull() ?: return@launch
            area.value = info
            val old = announcedArea
            announcedArea = info
            val parts = ArrayList<String>()
            val v = settings.areaVerbosity
            fun changed(item: LiveItem, new: String?, prev: String?, detailed: String) {
                if (settings.isOn(item) && !new.isNullOrBlank() && new != prev) parts.add(if (v == Verbosity.DETAILED) detailed else new)
            }
            changed(LiveItem.COUNTRY, info.country, old?.country, "Entering ${info.country}")
            changed(LiveItem.STATE, info.state, old?.state, "Entering the state of ${info.state}")
            changed(LiveItem.COUNTY, info.county, old?.county, "Entering ${info.county}")
            changed(LiveItem.CITY, info.city, old?.city, "Entering ${info.city}")
            changed(LiveItem.NEIGHBORHOOD, info.neighborhood, old?.neighborhood, "Neighborhood: ${info.neighborhood}")
            if (settings.isOn(LiveItem.ZIP) && !info.zip.isNullOrBlank() && info.zip != old?.zip)
                parts.add(if (v == Verbosity.BRIEF) spellZip(info.zip) else "zip code ${spellZip(info.zip)}")
            if (settings.isOn(LiveItem.ADDRESS) && fix.accuracy <= MAX_ACCURACY && !info.address.isNullOrBlank() && info.address != old?.address)
                parts.add(if (v == Verbosity.BRIEF) info.address else "near ${info.address}")
            if (parts.isNotEmpty()) say(parts.joinToString(", "))
            if (street.value == null && !info.street.isNullOrBlank()) street.value = info.street
        }
    }

    private fun spellZip(z: String) = z.toCharArray().joinToString(" ")

    // Street names and intersections, from OpenStreetMap.
    private fun checkStreets(fix: Fix) {
        if (streetJob?.isActive == true) return
        if (!settings.isOn(LiveItem.STREET) && !settings.isOn(LiveItem.INTERSECTIONS)) return
        streetJob = scope.launch {
            val map = StreetMap.around(fix.pos) ?: return@launch
            // With a weak GPS signal, Treker stays quiet about streets and intersections rather
            // than risk naming the wrong one.
            if (fix.accuracy > MAX_ACCURACY) return@launch
            if (settings.isOn(LiveItem.STREET)) {
                val near = map.nearestStreet(fix.pos, fix.course)
                val name = near?.takeIf { it.second < 20 + fix.accuracy / 2 }?.first?.name
                if (name != null && name != street.value) {
                    // Only announce a new street once it's confirmed twice, so crossing a street isn't "now on".
                    if (name == pendingStreet) pendingStreetCount++ else { pendingStreet = name; pendingStreetCount = 1 }
                    if (pendingStreetCount >= 2) {
                        street.value = name
                        say(when (settings.streetVerbosity) {
                            Verbosity.BRIEF -> name
                            Verbosity.NORMAL -> "On $name"
                            Verbosity.DETAILED -> "On $name" + (near.first.kind.let { streetKind(it) }?.let { ", $it" } ?: "")
                        })
                    }
                }
            }
            if (settings.isOn(LiveItem.INTERSECTIONS)) {
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
        if (!settings.isOn(LiveItem.PLACES) || placesJob?.isActive == true || fix.accuracy > MAX_ACCURACY) return
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

    /** "Where am I": everything known about this spot, regardless of the on/off settings. */
    fun whereAmI(): String {
        val fix = app.location.fix.value ?: return "No GPS position yet."
        val a = area.value
        val parts = ArrayList<String>()
        street.value?.let { parts.add("On $it") }
        a?.address?.let { parts.add("near $it") }
        listOfNotNull(a?.neighborhood, a?.city, a?.county, a?.state).forEach { parts.add(it) }
        a?.zip?.let { parts.add("zip code ${spellZip(it)}") }
        a?.country?.let { parts.add(it) }
        if (fix.accuracy > MAX_ACCURACY) parts.add("GPS is weak, accurate only to about ${Geo.distanceWords(fix.accuracy.toDouble(), settings.units)}")
        return if (parts.isEmpty()) "Looking up where you are." else parts.joinToString(", ") + "."
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
