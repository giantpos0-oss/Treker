package com.willwalsh.treker.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Corrections
import com.willwalsh.treker.data.Reports
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Your map reports and the fixes you made on your phone, on the Help and settings tab. */
@Composable
fun ReportsSection() {
    val context = LocalContext.current
    val app = context.applicationContext as TrekerApp
    Heading("Map reports and fixes")
    Text("To report a map problem, use Report a map problem on the Explore tab, the Report a problem action on any place, " +
        "or Report a problem here in map browsing.", fontSize = 16.sp)

    val waiting = Reports.waiting
    if (waiting > 0) {
        BigButton("Send $waiting waiting ${if (waiting == 1) "report" else "reports"} now") {
            app.scope.launch {
                val n = Reports.sendWaiting()
                app.speaker.say(if (n == waiting) "All sent." else "$n sent. The rest will be tried again later.", true)
            }
        }
    }
    val sent = Reports.items.filter { it.noteId != null }.takeLast(10).reversed()
    for (r in sent) {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.created))
        BigButton("Open report from $date: ${r.problem.label}${r.placeName?.let { ", $it" } ?: ""}") {
            Reports.noteLink(r)?.let { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    if (Corrections.items.isNotEmpty()) Text("Fixes on this phone. Each one stays until you undo it.", fontSize = 16.sp)
    for (c in Corrections.items.toList()) {
        BigButton("Undo: ${c.spoken()}") {
            Corrections.remove(c)
            app.scope.launch { com.willwalsh.treker.data.StreetMap.clear() }
            app.speaker.say("Undone.", true)
        }
    }
}
