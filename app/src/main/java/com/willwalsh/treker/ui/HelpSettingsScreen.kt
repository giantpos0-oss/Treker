package com.willwalsh.treker.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.treker.AnnounceDistance
import com.willwalsh.treker.ArrivalDistance
import com.willwalsh.treker.GpsAccuracy
import com.willwalsh.treker.BuildConfig
import com.willwalsh.treker.DirectionStyle
import com.willwalsh.treker.LiveItem
import com.willwalsh.treker.RouteType
import com.willwalsh.treker.ShakeStrength
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.Units
import com.willwalsh.treker.Verbosity
import com.willwalsh.treker.data.Geo
import java.util.Locale

private typealias Page = SettingsPage

private const val REPO = "https://github.com/giantpos0-oss/Treker/blob/main/docs"

@Composable
fun HelpSettingsScreen() {
    val context = LocalContext.current
    val page = AppNav.settingsPage.value
    BackHandler(enabled = AppNav.canGoUp) { AppNav.up() }

    fun open(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        when (page) {
            Page.MAIN -> {
                BigButton("Open user guide in browser") { open("$REPO/user-guide.md") }
                BigButton("Open release notes in browser") { open("$REPO/release-notes.md") }
                Text("Treker version ${BuildConfig.VERSION_NAME}", fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
                Heading("Settings")
                for (p in listOf(Page.SPEECH, Page.ANNOUNCEMENTS, Page.ROUTES, Page.UNITS, Page.ACCURACY, Page.GESTURES, Page.MAPS, Page.REPORTS)) {
                    BigButton(p.title) { AppNav.openPage(p) }
                }
                Heading("Help")
                BigButton(Page.GUIDE.title) { AppNav.openPage(Page.GUIDE) }
                Heading("Map data")
                Text("Map data © OpenStreetMap contributors, available under the Open Database License. " +
                    "Routes by Valhalla. Public transit trips by Transitous, transitous.org, from open timetable data. " +
                    "Zip code areas from the U.S. Census Bureau.", fontSize = 16.sp)
                BigButton("Transitous data sources") { open("https://transitous.org/sources/") }
            }
            Page.SPEECH -> SpeechSettings()
            Page.ANNOUNCEMENTS -> AnnouncementSettings()
            Page.ROUTES -> RouteSettings()
            Page.UNITS -> UnitSettings()
            Page.ACCURACY -> AccuracySettings()
            Page.GESTURES -> GestureSettings()
            Page.MAPS -> OfflineMapsSection()
            Page.REPORTS -> ReportsSection()
            Page.GUIDE -> {
                val guide = remember { runCatching { context.assets.open("user-guide.md").bufferedReader().use { it.readText() } }.getOrDefault("") }
                GuideText(guide)
            }
        }
    }
}

/** True when no single option is open, so the rest of a settings page should show. */
private val pageShowing: Boolean get() = AppNav.settingsOption.value == null

/**
 * One option in a settings page: a button saying its name and current value. Pressing it opens the
 * option on its own screen; Navigate up comes back.
 */
@Composable
fun OptionPage(title: String, current: String, content: @Composable () -> Unit) {
    when (AppNav.settingsOption.value) {
        null -> BigButton(if (current.isBlank()) title else "$title: $current") { AppNav.settingsOption.value = title }
        title -> content()
        else -> {}
    }
}

/** An option with exactly one choice selected; on its screen they're radio buttons. */
@Composable
fun <T> Choices(title: String, options: List<Pair<T, String>>, selected: T, extra: @Composable () -> Unit = {}, onSelect: (T) -> Unit) {
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    OptionPage(title, current) {
        extra()
        Column(Modifier.selectableGroup()) {
            for ((value, label) in options) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == selected, onClick = null)
                    Text(label, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, spoken: (Float) -> String,
                          onChange: (Float) -> Unit, onDone: () -> Unit) {
    Text(label, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
    Slider(
        value = value, onValueChange = onChange, onValueChangeFinished = onDone,
        valueRange = range, steps = steps,
        modifier = Modifier.semantics { contentDescription = label; stateDescription = spoken(value) },
    )
}

@Composable
private fun SpeechSettings() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val s = app.settings
    val sp = app.speaker
    var allLanguages by remember { mutableStateOf(false) }

    if (pageShowing) {
        Text("Treker's own voice speaks live exploration, What's Around and guidance, so it works with the screen off. " +
            "It's separate from your screen reader's voice.", fontSize = 16.sp)
        BigButton("Test speech") { sp.say("This is Treker. Approaching Main Street and Oak Avenue, 100 feet.", true) }
    }

    Choices("Speech engine", listOf("" to "Phone's default engine") + sp.engines.toList(), s.speechEngine) {
        s.speechEngine = it; s.speechVoice = ""
        sp.reload()
        sp.say("Speech engine changed.", true)
    }

    val lang = Locale.getDefault().language
    val voices = sp.voices.toList().filter { allLanguages || it.first.startsWith(lang) || it.second.startsWith(Locale.getDefault().displayLanguage) }
    if (sp.voices.isEmpty()) { if (pageShowing) Text("This engine doesn't list its voices. Its default voice is used.", fontSize = 16.sp) }
    else Choices("Voice", listOf("" to "Engine's default voice") + voices, s.speechVoice,
        extra = { SwitchRow("Show voices for all languages", allLanguages) { allLanguages = it } }) {
        sp.setVoice(it)
        if (it.isBlank()) sp.reload()
        sp.say("This is the voice you chose.", true)
    }

    if (pageShowing) {
        // Big steps: half a step at a time, up to 8 times normal speed.
        LabeledSlider("Speech rate", s.speechRate, 0.5f..8f, 14, { "%.1f".format(it) },
            { s.speechRate = Math.round(it * 2) / 2f; sp.rate = s.speechRate }, { sp.say("Speech rate ${"%.1f".format(s.speechRate)}", true) })
        LabeledSlider("Pitch", s.speechPitch, 0.5f..2f, 14, { "%.1f".format(it) },
            { s.speechPitch = Math.round(it * 10) / 10f; sp.pitch = s.speechPitch }, { sp.say("Pitch ${"%.1f".format(s.speechPitch)}", true) })
        LabeledSlider("Volume", s.speechVolume, 0.2f..1f, 7, { "${(it * 100).toInt()} percent" },
            { s.speechVolume = Math.round(it * 10) / 10f }, { sp.say("Volume ${(s.speechVolume * 100).toInt()} percent", true) })
    }
}

@Composable
private fun AnnouncementSettings() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val s = app.settings
    fun opts(brief: String, normal: String, detailed: String) = listOf(
        Verbosity.BRIEF to "Brief, for example: $brief", Verbosity.NORMAL to "Normal, for example: $normal",
        Verbosity.DETAILED to "Detailed, for example: $detailed")

    if (pageShowing) Text("Choose what live exploration announces, and how much it says for each.", fontSize = 16.sp)
    Choices("Streets", opts("Main Street", "On Main Street", "On Main Street, residential street"), s.streetVerbosity) { s.streetVerbosity = it }
    Choices("Intersections", opts("Main Street and Oak Avenue", "Approaching Main Street and Oak Avenue, 100 feet",
        "Approaching 4-way intersection, Main Street and Oak Avenue, 100 feet, ahead"), s.intersectionVerbosity) { s.intersectionVerbosity = it }
    Choices("Announce intersections ahead", AnnounceDistance.entries.map { it to it.label }, s.intersectionDistance) { s.intersectionDistance = it }
    Choices("Places you pass", opts("Corner Cafe", "Corner Cafe, to the right, 50 feet", "Corner Cafe, Cafe, to the right, 50 feet"), s.placeVerbosity) { s.placeVerbosity = it }
    Choices("Country, state, county, city and zip code", opts("Austin", "Austin, zip code 7 8 7 0 1", "Entering Austin, zip code 7 8 7 0 1"),
        s.areaVerbosity) { s.areaVerbosity = it }

    if (pageShowing) SwitchRow("Announce when the GPS signal turns weak or good again", s.announceGps) { s.announceGps = it }
    val guiding = LiveItem.entries.filter { s.isOn(it, guiding = true) }
    OptionPage("Announce while guiding", "${guiding.size} of ${LiveItem.entries.size} on") {
        Text("Used instead of the Explore tab's switches while route guidance is running, in every travel mode.", fontSize = 16.sp)
        for (item in LiveItem.entries) SwitchRow(item.label, s.isOn(item, guiding = true)) { s.set(item, it, guiding = true) }
    }
}

@Composable
private fun RouteSettings() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val s = app.settings
    Choices("Route type", listOf(RouteType.FASTEST to "Fastest", RouteType.SHORTEST to "Shortest distance"), s.routeType) { s.routeType = it }

    if (!pageShowing) return
    Text("Walking", fontSize = 19.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    SwitchRow("Avoid stairs", s.avoidStairs) { s.avoidStairs = it }
    SwitchRow("Prefer sidewalks and footpaths", s.preferSidewalks) { s.preferSidewalks = it }
    SwitchRow("Avoid alleys", s.avoidAlleys) { s.avoidAlleys = it }
    SwitchRow("Prefer lit streets", s.preferLitStreets) { s.preferLitStreets = it }

    Text("Driving", fontSize = 19.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    SwitchRow("Avoid highways", s.avoidHighways) { s.avoidHighways = it }
    SwitchRow("Avoid toll roads", s.avoidTolls) { s.avoidTolls = it }
    SwitchRow("Avoid ferries", s.avoidFerries) { s.avoidFerries = it }

    Text("Testing", fontSize = 19.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    SwitchRow("Practice mode: guidance moves along the route by itself", s.simulate) { s.simulate = it }
    Text("Route settings apply to the next route you start.", fontSize = 16.sp)
}

@Composable
private fun UnitSettings() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val s = app.settings
    Choices("Distances", Units.entries.map { it to it.label }, s.units) { s.units = it }
    Choices("Directions", DirectionStyle.entries.map { it to it.label }, s.directionStyle) { s.directionStyle = it }
}

@Composable
private fun AccuracySettings() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val s = app.settings
    val fix = app.location.fix.value
    if (pageShowing) Text(if (fix == null) "No GPS position yet." else "Right now, GPS is accurate to about ${Geo.distanceWords(fix.accuracy.toDouble(), s.units)}.",
        fontSize = 17.sp)
    Choices("Required GPS accuracy", GpsAccuracy.entries.map { it to it.label }, s.gpsAccuracy) { s.gpsAccuracy = it }
    if (pageShowing) Text("Treker only announces intersections, addresses and places you pass, and only says you've arrived, when your GPS position is " +
        "at least this precise. Street names can be given with GPS up to about 100 feet off, but only when one street is clearly the nearest. " +
        "When GPS isn't good enough, Treker says nothing rather than guess. Out in the open, most phones " +
        "manage about 15 feet; between tall buildings, under trees or indoors, GPS can't reach 30 feet, and Treker will tell you so.",
        fontSize = 16.sp)
    Choices("Say you've arrived when walking within", ArrivalDistance.entries.map { it to it.label }, s.arrivalDistance) { s.arrivalDistance = it }
    if (pageShowing) Text("In the last 150 feet of a walk, Treker counts down the distance and says which way the destination is, " +
        "right up to the door, not just the end of the street.", fontSize = 16.sp)
}

@Composable
private fun GestureSettings() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val s = app.settings
    if (pageShowing) SwitchRow("Shake the phone for Where am I", s.shakeWhereAmI) { s.shakeWhereAmI = it }
    Choices("How hard to shake", ShakeStrength.entries.map { it to it.label }, s.shakeStrength) { s.shakeStrength = it }
    if (pageShowing) Text("Works with the screen off and in other apps, while live exploration is running. " +
        "If walking sets it off, choose Firm; if it's hard to trigger, choose Light.", fontSize = 16.sp)
    if (pageShowing) SwitchRow("Vibrate for shakes and in What's Around", s.vibrate) { s.vibrate = it }
    Choices("What's Around distance", listOf(50, 100, 150, 300, 500).map { it to "Up to ${Geo.distanceWords(it.toDouble(), s.units)}" },
        s.placesRadius) { s.placesRadius = it }
}

/** Shows the bundled user guide: # lines become headings, the rest paragraphs. */
@Composable
private fun GuideText(md: String) {
    for (block in md.split(Regex("\n\\s*\n"))) {
        val t = block.trim()
        if (t.isEmpty()) continue
        if (t.startsWith("#")) Heading(t.trimStart('#').trim())
        else Text(t.replace(Regex("^- ", RegexOption.MULTILINE), "• "), fontSize = 17.sp, modifier = Modifier.padding(vertical = 4.dp))
    }
}
