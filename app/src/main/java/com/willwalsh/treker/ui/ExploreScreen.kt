package com.willwalsh.treker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.treker.LiveItem
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Offline
import com.willwalsh.treker.data.Place
import kotlinx.coroutines.launch

@Composable
fun ExploreScreen() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val s = app.settings
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf<List<Pair<Place, Double>>?>(null) }
    var placesNote by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        // What's Around is the very first thing on the screen.
        SwitchRow("What's Around", app.whatsAround.running.value) { on ->
            s.whatsAround = on
            if (on) app.whatsAround.start() else app.whatsAround.stop()
        }
        val pointing = app.whatsAround.pointingAt.value
        if (pointing.isNotBlank()) Text(pointing, fontSize = 18.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })

        BigButton(if (s.liveExploration) "Pause" else "Resume") { app.togglePause() }
        Text(app.explorer.status.value, fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
        val fix = app.location.fix.value
        if (fix != null && !Offline.hasMapFor(fix.pos)) {
            Text("There's no offline map for where you are yet.", fontSize = 18.sp)
            BigButton("Get the offline map") { AppNav.tab.value = Tab.HELP }
        }

        BigButton("Where am I") { app.speaker.say(app.explorer.whereAmI(), true) }
        BigButton("Nearest intersection") { scope.launch { app.speaker.say(app.explorer.nearestIntersection(), true) } }
        BigButton("Places nearby") {
            scope.launch {
                try {
                    val list = app.explorer.nearbyPlaces()
                    places = list
                    placesNote = if (list.isEmpty()) "No places found nearby." else "${list.size} places nearby, nearest first."
                } catch (e: Exception) { places = emptyList(); placesNote = e.message ?: "Couldn't get places." }
                app.speaker.say(placesNote, true)
            }
        }
        BigButton("Report a map problem") {
            val here = app.location.fix.value?.pos
            if (here == null) app.speaker.say("No GPS position yet.", true)
            else AppNav.report.value = ReportRequest(here, heard = app.explorer.history.firstOrNull())
        }
        places?.let { list ->
            Heading("Places nearby")
            Text(placesNote, fontSize = 16.sp)
            list.forEach { (p, d) -> PlaceRow(p, d, app) }
        }

        Heading("Announce while moving")
        for (item in LiveItem.entries) {
            // When a switch is on, it shows the latest information, so a screen reader reads it while moving through them.
            val value = if (s.isOn(item)) liveValue(app, item) else null
            SwitchRow(if (value.isNullOrBlank()) item.label else "${item.label}: $value", s.isOn(item)) { s.set(item, it) }
        }

        Heading("Recent announcements")
        val history = app.explorer.history
        if (history.isEmpty()) Text("Nothing announced yet.", fontSize = 16.sp)
        history.take(15).forEach { Text(it, fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp)) }
    }
}

/** The latest value for one live exploration item, or null if there isn't one yet. */
fun liveValue(app: TrekerApp, item: LiveItem): String? {
    val a = app.explorer.area.value
    return when (item) {
        LiveItem.COUNTRY -> a?.country
        LiveItem.STATE -> a?.state
        LiveItem.COUNTY -> a?.county
        LiveItem.CITY -> a?.city
        LiveItem.NEIGHBORHOOD -> a?.neighborhood
        // Digits spaced out so it's read as a zip code, not a number.
        LiveItem.ZIP -> a?.zip?.toCharArray()?.joinToString(" ")
        LiveItem.STREET -> app.explorer.street.value
        LiveItem.ADDRESS -> a?.address
        LiveItem.INTERSECTIONS -> app.explorer.lastIntersection.value
        LiveItem.PLACES -> app.explorer.lastPlace.value
    }
}
