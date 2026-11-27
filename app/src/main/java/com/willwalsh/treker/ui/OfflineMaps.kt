package com.willwalsh.treker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Offline
import com.willwalsh.treker.data.RegionInfo
import com.willwalsh.treker.data.Regions
import kotlinx.coroutines.launch

/** Download, update and remove offline maps. Lives on the Help and settings tab. */
@Composable
fun OfflineMapsSection() {
    val app = LocalContext.current.applicationContext as TrekerApp
    // Bumped after a download or delete so the installed list redraws.
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { if (Regions.catalog.value.isEmpty()) Regions.loadCatalog() }

    val installed = remember(version) { Offline.installed }
    if (installed.isEmpty()) Text("No maps downloaded yet. BkindExplorer needs the map for your area to work.", fontSize = 16.sp)
    for (r in installed) {
        BigButton("Delete ${r.name} map") {
            app.scope.launch {
                Regions.delete(r.id); version++
                app.speaker.say("Deleted the ${r.name} map.", true)
            }
        }
    }

    val here = app.location.fix.value?.pos
    val catalog = Regions.catalog.value
    if (Regions.catalogStatus.value.isNotBlank()) Text(Regions.catalogStatus.value, fontSize = 16.sp)
    if (catalog.isEmpty()) BigButton("Check for maps") { app.scope.launch { Regions.loadCatalog() } }

    // The map covering where you are comes first.
    val sorted = catalog.sortedByDescending { here != null && it.covers(here) }
    for (r in sorted) RegionButton(r, here?.let { r.covers(it) } == true, onDone = { version++ })
}

@Composable
private fun RegionButton(r: RegionInfo, coversYou: Boolean, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as TrekerApp
    val p = Regions.progress[r.id]
    val installed = Regions.isInstalled(r.id)
    val label = when {
        p != null -> "Downloading ${r.name}, ${(p * 100).toInt()} percent"
        installed && Regions.hasUpdate(r) -> "Update ${r.name} map, newer map built ${r.built}"
        installed -> "${r.name} map is up to date, built ${r.built}. Download again"
        else -> "Download ${r.name} map, ${Regions.sizeWords(r.totalSize)}" + if (coversYou) ", covers where you are" else ""
    }
    BigButton(label, Modifier.padding(vertical = 2.dp)) {
        if (p != null) return@BigButton
        app.speaker.say("Downloading the ${r.name} map. You can keep using BkindExplorer.", true)
        app.scope.launch {
            try {
                Regions.download(r)
                app.speaker.say("The ${r.name} map is ready.", true)
            } catch (e: Exception) {
                app.speaker.say("The ${r.name} map didn't download: ${e.message}", true)
            }
            onDone()
        }
    }
}
