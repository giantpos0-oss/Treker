package com.willwalsh.treker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Corrections
import com.willwalsh.treker.data.LatLon
import com.willwalsh.treker.data.MapCorrection
import com.willwalsh.treker.data.Place
import com.willwalsh.treker.data.Problem
import com.willwalsh.treker.data.Report
import com.willwalsh.treker.data.Reports
import kotlinx.coroutines.launch

/** Everything a report starts with: where, which place, and what BkindExplorer said there. */
data class ReportRequest(val pos: LatLon, val place: Place? = null, val heard: String? = null, val problem: Problem? = null)

/**
 * Report a map problem in three steps: pick what's wrong, optionally say more, press Send.
 * Where it makes sense, the fix can also take effect on this phone straight away.
 */
@Composable
fun ReportDialog(req: ReportRequest, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as TrekerApp
    var problem by remember { mutableStateOf(req.problem ?: if (req.place != null) Problem.PLACE_WRONG else null) }
    var details by remember { mutableStateOf("") }
    var correctName by remember { mutableStateOf("") }
    var fixHere by remember { mutableStateOf(true) }
    val street = app.explorer.street.value

    val problems = if (req.place != null) listOf(Problem.PLACE_WRONG, Problem.PLACE_NAME, Problem.ADDRESS, Problem.OTHER)
    else Problem.entries.toList()

    // Which local fix fits this problem, if any.
    val localFix: String? = when (problem) {
        Problem.PLACE_WRONG -> if (req.place != null) "Also hide ${req.place.name} on my phone now" else null
        Problem.PLACE_NAME -> if (req.place != null) "Also use the correct name on my phone now" else null
        Problem.PLACE_MISSING -> "Also add this place on my phone now, at this spot"
        Problem.STREET_NAME -> if (street != null) "Also use the correct name for $street on my phone now" else null
        else -> null
    }
    val needsName = problem == Problem.PLACE_NAME || problem == Problem.PLACE_MISSING || problem == Problem.STREET_NAME

    fun send() {
        val p = problem ?: return
        val extra = if (needsName && correctName.isNotBlank()) "Correct name: ${correctName.trim()}. " else ""
        val report = Report(
            problem = p, pos = req.pos, details = (extra + details).trim(),
            placeName = req.place?.name ?: if (p == Problem.STREET_NAME) street else null,
            placeKey = req.place?.placeId, heard = req.heard,
        )
        if (fixHere && localFix != null) {
            val name = correctName.trim()
            when (p) {
                Problem.PLACE_WRONG -> req.place?.let { Corrections.add(MapCorrection(MapCorrection.Type.HIDE_PLACE, it.name, "", it.pos, it.placeId)) }
                Problem.PLACE_NAME -> if (name.isNotBlank()) req.place?.let { Corrections.add(MapCorrection(MapCorrection.Type.RENAME_PLACE, it.name, name, it.pos, it.placeId)) }
                Problem.PLACE_MISSING -> if (name.isNotBlank()) Corrections.add(MapCorrection(MapCorrection.Type.ADD_PLACE, "", name, req.pos, null))
                Problem.STREET_NAME -> if (name.isNotBlank() && street != null) Corrections.add(MapCorrection(MapCorrection.Type.RENAME_STREET, street, name, req.pos, null))
                else -> {}
            }
            app.scope.launch { com.willwalsh.treker.data.StreetMap.clear() }
        }
        onClose()
        app.speaker.say("Sending your report.", true)
        app.scope.launch {
            val ok = Reports.submit(report)
            app.speaker.say(
                if (ok) "Report sent to OpenStreetMap. Local mappers can now fix it, and the fix reaches BkindExplorer with the next monthly map update."
                else "Report saved. It couldn't be sent right now, so BkindExplorer will send it later.",
                true,
            )
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Report a map problem" + (req.place?.let { ": ${it.name}" } ?: "")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("What's wrong?", fontSize = 18.sp)
                Column(Modifier.selectableGroup()) {
                    for (pr in problems) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = problem == pr, role = Role.RadioButton, onClick = { problem = pr }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = problem == pr, onClick = null)
                            Text(pr.label, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (needsName) {
                    OutlinedTextField(
                        value = correctName, onValueChange = { correctName = it },
                        label = { Text(if (problem == Problem.PLACE_MISSING) "Name of the missing place" else "Correct name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = details, onValueChange = { details = it },
                    label = { Text("Tell mappers more (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (localFix != null) SwitchRow(localFix, fixHere) { fixHere = it }
                Text("Your report becomes a public OpenStreetMap note at this spot, so volunteers nearby can fix the map. " +
                    "It doesn't include your name.", fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { send() }, enabled = problem != null) { Text("Send report") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
