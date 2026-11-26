package com.willwalsh.treker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.treker.LiveItem
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.Problem
import kotlinx.coroutines.launch

private const val STEPS = "Step-by-step directions"
private const val ANNOUNCE = "Announcements while guiding"

/**
 * The first tab while guidance is running: where you're going, the next instruction and the distances,
 * all updating as you move, and every guidance control in one place.
 */
@Composable
fun ActiveRouteScreen() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val g = app.guidance
    val s = app.settings
    val scope = rememberCoroutineScope()
    BackHandler(enabled = AppNav.routePage.value != null) { AppNav.up() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        when (AppNav.routePage.value) {
            STEPS -> {
                if (g.steps.isEmpty()) Text("No more instructions.", fontSize = 17.sp)
                g.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", fontSize = 18.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            }
            ANNOUNCE -> {
                Text("What's announced while you're being guided, in every travel mode. The Explore tab's switches are used when you're not being guided.",
                    fontSize = 16.sp)
                for (item in LiveItem.entries) SwitchRow(item.label, s.isOn(item, guiding = true)) { s.set(item, it, guiding = true) }
            }
            else -> {
                // Live information: these read state that changes with every GPS fix, so they update by themselves.
                val dest = g.destination.value?.name.orEmpty()
                Text("${g.mode.value?.label ?: ""} guidance. To. $dest.", fontSize = 19.sp, modifier = Modifier.padding(top = 8.dp))
                if (g.current.value.isNotBlank()) Text("Next: ${g.current.value}", fontSize = 19.sp, modifier = Modifier.padding(vertical = 4.dp))
                Text("Remaining: ${Geo.distanceWords(g.remaining.value, s.units)}.", fontSize = 17.sp)
                Text("Traveled: ${Geo.distanceWords(g.traveled.value, s.units)}.", fontSize = 17.sp)
                if (g.status.value.isNotBlank()) Text(g.status.value, fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))

                BigButton("Repeat instruction") { g.repeat() }
                BigButton("Where am I") { scope.launch { app.speaker.say(app.explorer.whereAmI(), true) } }
                BigButton("Recalculate route") { g.recalculate() }
                BigButton(STEPS) { AppNav.routePage.value = STEPS }
                BigButton(ANNOUNCE) { AppNav.routePage.value = ANNOUNCE }
                BigButton("Report a route problem") {
                    app.location.fix.value?.pos?.let {
                        AppNav.report.value = ReportRequest(it, heard = g.current.value, problem = Problem.ROUTE)
                    }
                }
                BigButton("Stop guidance") { g.stop() }
            }
        }
    }
}
