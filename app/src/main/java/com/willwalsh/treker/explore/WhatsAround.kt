package com.willwalsh.treker.explore

import androidx.compose.runtime.mutableStateOf
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.Offline
import com.willwalsh.treker.data.LatLon
import com.willwalsh.treker.data.StreetMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What's Around: point the top of the phone in a direction to hear the places and intersections
 * that way. Everything comes from the offline map.
 */
class WhatsAround(private val app: TrekerApp, private val scope: CoroutineScope) {
    private data class Target(val key: String, val name: String, val pos: LatLon)

    val running = mutableStateOf(false)
    val pointingAt = mutableStateOf("")
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        running.value = true
        app.compass.start()
        if (!app.compass.available) app.speaker.say("This phone has no compass sensor, so What's Around can't tell where you're pointing.")
        else app.speaker.say("What's Around on. Point the top of your phone.", interrupt = true)
        job = scope.launch {
            var targets = emptyList<Target>()
            var loadedAt: LatLon? = null
            var lastKey: String? = null
            var lastSpoke = 0L
            while (isActive) {
                val fix = app.location.fix.value
                val heading = app.compass.heading.value
                if (fix != null && (loadedAt == null || Geo.distance(loadedAt, fix.pos) > 40)) {
                    loadedAt = fix.pos
                    val radius = app.settings.placesRadius.toDouble()
                    val list = ArrayList<Target>()
                    try {
                        Offline.pois(fix.pos, radius + 50).forEach { list.add(Target(it.placeId ?: it.name, it.name, it.pos)) }
                    } catch (e: Exception) {
                        app.speaker.say(e.message ?: "No offline map here.")
                    }
                    StreetMap.around(fix.pos)?.clusters?.forEach { list.add(Target("i${it.node}", it.spoken(), it.pos)) }
                    targets = list
                }
                if (fix != null && heading != null) {
                    val radius = app.settings.placesRadius.toDouble()
                    val hit = targets
                        .map { Triple(it, Geo.distance(fix.pos, it.pos), Geo.bearing(fix.pos, it.pos)) }
                        .filter { it.second in 3.0..radius && Math.abs(Geo.angleDiff(heading, it.third)) < 15 }
                        .minByOrNull { Math.abs(Geo.angleDiff(heading, it.third)) * 4 + it.second / 10 }
                    val now = System.currentTimeMillis()
                    if (hit != null && hit.first.key != lastKey && now - lastSpoke > 600) {
                        lastKey = hit.first.key; lastSpoke = now
                        val text = "${hit.first.name}, ${Geo.distanceWords(hit.second, app.settings.metric)}"
                        pointingAt.value = text
                        if (app.settings.vibrate) app.speaker.tick()
                        app.speaker.say(text, interrupt = true)
                    } else if (hit == null && lastKey != null && now - lastSpoke > 600) {
                        lastKey = null
                        pointingAt.value = "Nothing in this direction"
                    }
                }
                delay(150)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        if (running.value) { app.compass.stop(); app.speaker.say("What's Around off.", interrupt = true) }
        running.value = false
        pointingAt.value = ""
    }
}
