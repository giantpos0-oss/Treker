package com.willwalsh.treker.ui

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
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Offline
import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.Place
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen() {
    val app = LocalContext.current.applicationContext as TrekerApp
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        BigButton("Save my current location") {
            val fix = app.location.fix.value
            if (fix == null) { app.speaker.say("No GPS position yet.", true); return@BigButton }
            scope.launch {
                val info = runCatching { Offline.areaInfo(fix.pos) }.getOrNull()
                val name = info?.address ?: info?.street ?: "Saved spot ${app.favorites.items.size + 1}"
                app.favorites.add(Place(name, fix.pos, info?.address.orEmpty(), "Saved location"))
                app.speaker.say("Saved $name to favorites.", true)
            }
        }
        Heading("Favorites")
        val here = app.location.fix.value?.pos
        if (app.favorites.items.isEmpty()) Text("No favorites yet. Add places from search results, places nearby, or save where you are.", fontSize = 16.sp)
        app.favorites.items.toList()
            .sortedBy { p -> here?.let { Geo.distance(it, p.pos) } ?: 0.0 }
            .forEach { p -> PlaceRow(p, here?.let { Geo.distance(it, p.pos) }, app, inFavorites = true) }
    }
}
