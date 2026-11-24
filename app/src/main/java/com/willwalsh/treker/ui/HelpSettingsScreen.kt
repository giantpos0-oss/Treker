package com.willwalsh.treker.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.treker.BuildConfig
import com.willwalsh.treker.DirectionStyle
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.GooglePlaces

private const val REPO = "https://github.com/giantpos0-oss/Treker/blob/main/docs"

@Composable
fun HelpSettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as TrekerApp
    val s = app.settings
    val guide = remember { runCatching { context.assets.open("user-guide.md").bufferedReader().use { it.readText() } }.getOrDefault("") }

    fun open(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        BigButton("Open user guide in browser") { open("$REPO/user-guide.md") }
        BigButton("Open release notes in browser") { open("$REPO/release-notes.md") }
        Text("Treker version ${BuildConfig.VERSION_NAME}" +
            if (GooglePlaces.hasKey) "" else ". Google key not set up in this build.", fontSize = 16.sp)

        Heading("Settings")
        SwitchRow("Use meters instead of feet", s.metric) { s.metric = it }
        SwitchRow("Directions as clock face (off for compass directions)", s.directionStyle == DirectionStyle.CLOCK) {
            s.directionStyle = if (it) DirectionStyle.CLOCK else DirectionStyle.COMPASS
        }
        SwitchRow("Vibrate in What's Around", s.vibrate) { s.vibrate = it }
        SwitchRow("Practice mode: guidance moves along the route by itself", s.simulate) { s.simulate = it }

        Text("Speech speed", fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
        Slider(
            value = s.speechRate,
            onValueChange = { s.speechRate = (Math.round(it * 10) / 10f); app.speaker.rate = s.speechRate },
            onValueChangeFinished = { app.speaker.say("Speech speed ${s.speechRate}", true) },
            valueRange = 0.5f..3f, steps = 24,
            modifier = Modifier.semantics { contentDescription = "Speech speed"; stateDescription = "${s.speechRate}" },
        )

        Text("What's Around distance", fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
        for (r in listOf(50, 100, 150, 300, 500)) {
            val words = com.willwalsh.treker.data.Geo.distanceWords(r.toDouble(), s.metric)
            SwitchRow("Up to $words", s.placesRadius == r) { if (it) s.placesRadius = r }
        }

        Heading("Help")
        GuideText(guide)

        Heading("Map data")
        Text("Places, search and navigation from Google Maps. Streets and intersections © OpenStreetMap contributors, " +
            "available under the Open Database License.", fontSize = 16.sp)
    }
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
