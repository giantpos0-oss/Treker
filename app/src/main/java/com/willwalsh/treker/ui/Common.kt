package com.willwalsh.treker.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.Place
import com.willwalsh.treker.nav.TravelMode

@Composable
fun TrekerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Color(0xFFFFD54F), onPrimary = Color.Black, background = Color.Black, surface = Color.Black),
        content = content,
    )
}

@Composable
fun Heading(text: String) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() })
}

/** An on/off setting: the whole row is one switch for screen readers. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun BigButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp)) {
        Text(label, fontSize = 18.sp)
    }
}

/** What can be done with a place, used both as screen reader actions and in the actions dialog. */
fun placeActions(app: TrekerApp, activity: Activity?, place: Place, inFavorites: Boolean): List<Pair<String, () -> Unit>> {
    val list = ArrayList<Pair<String, () -> Unit>>()
    for (m in TravelMode.entries) list.add("Start in ${m.label.lowercase()} mode" to {
        AppNav.tab.value = Tab.ROUTES; app.guidance.start(place, m)
    })
    list.add("Virtual map exploration" to {
        AppNav.tab.value = Tab.BROWSE
        AppNav.browseFrom.value = place
    })
    list.add("Report a problem with this place" to {
        AppNav.report.value = ReportRequest(place.pos, place = place)
    })
    if (inFavorites || app.favorites.contains(place)) {
        list.add("Remove from favorites" to { app.favorites.remove(place); app.speaker.say("Removed ${place.name} from favorites.", true) })
    } else {
        list.add("Add to favorites" to {
            app.favorites.add(place); app.speaker.say("Added ${place.name} to favorites.", true)
        })
    }
    return list
}

/**
 * One place in a list. With a screen reader, the actions (start in each mode, virtual map
 * exploration, add to favorites) are on the item itself; double tap opens them as buttons too.
 */
@Composable
fun PlaceRow(place: Place, distance: Double?, app: TrekerApp, inFavorites: Boolean = false) {
    val activity = LocalContext.current as? Activity
    var showActions by remember { mutableStateOf(false) }
    val actions = placeActions(app, activity, place, inFavorites)
    val label = buildString {
        append(place.name)
        if (distance != null) append(", ").append(Geo.distanceWords(distance, app.settings.units))
        if (place.kind.isNotBlank()) append(", ").append(place.kind)
        if (place.address.isNotBlank() && place.address != place.name) append(", ").append(place.address)
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClickLabel = "Show actions") { showActions = true }
            .semantics {
                customActions = actions.map { (name, run) -> CustomAccessibilityAction(name) { run(); true } }
            }
            .padding(vertical = 10.dp)
    ) {
        Text(label, fontSize = 18.sp)
    }
    HorizontalDivider()
    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text(place.name) },
            text = {
                Column {
                    for ((name, run) in actions) {
                        BigButton(name) { showActions = false; run() }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showActions = false }) { Text("Close") } },
        )
    }
}
