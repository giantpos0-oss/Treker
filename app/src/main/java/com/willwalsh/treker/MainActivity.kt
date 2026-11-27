package com.willwalsh.treker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.willwalsh.treker.explore.ExploreService
import com.willwalsh.treker.ui.ActiveRouteScreen
import com.willwalsh.treker.ui.AppNav
import com.willwalsh.treker.ui.BigButton
import com.willwalsh.treker.ui.BrowseScreen
import com.willwalsh.treker.ui.ExploreScreen
import com.willwalsh.treker.ui.FavoritesScreen
import com.willwalsh.treker.ui.ReportDialog
import com.willwalsh.treker.ui.HelpSettingsScreen
import com.willwalsh.treker.ui.RoutesScreen
import com.willwalsh.treker.ui.Tab
import com.willwalsh.treker.ui.TrekerTheme

class MainActivity : ComponentActivity() {
    private val hasLocation = mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TrekerApp
        refreshPermission()
        if (!hasLocation.value) requestPermissions()
        setContent {
            TrekerTheme {
                CompositionLocalProvider(LocalSettings provides app.settings) {
                    if (hasLocation.value) AppRoot()
                    else PermissionScreen { requestPermissions() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    private fun refreshPermission() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasLocation.value = ok
        // Live exploration is always available: start it as soon as there's permission.
        if (ok) ExploreService.start(this)
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        askPermissions.launch(perms.toTypedArray())
    }
}

@Composable
private fun PermissionScreen(onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("BkindExplorer needs your location to tell you where you are and guide you.", fontSize = 20.sp)
        BigButton("Allow location") { onAsk() }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AppRoot() {
    val tab = AppNav.tab.value
    val app = LocalContext.current.applicationContext as TrekerApp
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (AppNav.canGoUp) IconButton(onClick = { AppNav.up() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate up")
                    }
                },
                title = { Text(AppNav.title, modifier = Modifier.semantics { heading() }) },
                actions = {
                    TextButton(onClick = { app.togglePause() }) {
                        Text(if (app.settings.liveExploration) "Pause" else "Resume", fontSize = 18.sp)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { AppNav.tab.value = t },
                        icon = {
                            Icon(when (t) {
                                Tab.EXPLORE -> Icons.Filled.Explore
                                Tab.BROWSE -> Icons.Filled.Map
                                Tab.ROUTES -> Icons.Filled.Directions
                                Tab.FAVORITES -> Icons.Filled.Star
                                Tab.HELP -> Icons.Filled.Settings
                            }, contentDescription = null)
                        },
                        label = { Text(AppNav.label(t), fontSize = 12.sp, maxLines = 2) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AppNav.report.value?.let { req -> ReportDialog(req) { AppNav.report.value = null } }
            when (tab) {
                Tab.EXPLORE -> if (app.guidance.active.value) ActiveRouteScreen() else ExploreScreen()
                Tab.BROWSE -> BrowseScreen()
                Tab.ROUTES -> RoutesScreen()
                Tab.FAVORITES -> FavoritesScreen()
                Tab.HELP -> HelpSettingsScreen()
            }
        }
    }
}
