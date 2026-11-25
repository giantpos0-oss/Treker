package com.willwalsh.treker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.Offline
import com.willwalsh.treker.data.Place
import kotlinx.coroutines.launch

/** Search results stay while you move between tabs. */
private object RouteSearch {
    val results = mutableStateOf<List<Place>>(emptyList())
    val note = mutableStateOf("")
}

@Composable
fun RoutesScreen() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val g = app.guidance
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        focus.clearFocus()
        searching = true
        RouteSearch.note.value = "Searching."
        scope.launch {
            val here = app.location.fix.value?.pos
            val list = try {
                Offline.search(q, here)
            } catch (e: Exception) {
                RouteSearch.note.value = e.message ?: "Search didn't work."; searching = false
                app.speaker.say(RouteSearch.note.value, true); return@launch
            }
            RouteSearch.results.value = list
            RouteSearch.note.value = if (list.isEmpty()) "Nothing found for $q." else "${list.size} results."
            searching = false
            app.speaker.say(RouteSearch.note.value, true)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (g.active.value || g.status.value.isNotBlank()) {
            Heading("Guidance")
            Text(g.status.value, fontSize = 18.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (g.current.value.isNotBlank()) Text(g.current.value, fontSize = 20.sp, modifier = Modifier.padding(vertical = 6.dp))
            if (g.active.value) {
                BigButton("Repeat instruction") { g.repeat() }
                BigButton("Stop guidance") { g.stop() }
                BigButton("Report a route problem") {
                    app.location.fix.value?.pos?.let {
                        AppNav.report.value = ReportRequest(it, heard = g.current.value, problem = com.willwalsh.treker.data.Problem.ROUTE)
                    }
                }
                if (g.steps.isNotEmpty()) {
                    Heading("Steps")
                    g.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 17.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
        }

        Heading("Where to?")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Address or place name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        BigButton(if (searching) "Searching" else "Search") { search() }
        if (RouteSearch.note.value.isNotBlank()) Text(RouteSearch.note.value, fontSize = 16.sp)
        val here = app.location.fix.value?.pos
        RouteSearch.results.value.forEach { p -> PlaceRow(p, here?.let { Geo.distance(it, p.pos) }, app) }
    }
}
