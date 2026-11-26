package com.willwalsh.treker.ui

import androidx.compose.runtime.mutableStateOf
import com.willwalsh.treker.data.Place

/** The five tabs across the bottom, left to right. */
enum class Tab(val label: String) {
    EXPLORE("Explore"),
    BROWSE("Map browsing"),
    ROUTES("Routes"),
    FAVORITES("Favorites"),
    HELP("Help and settings"),
}

/** The pages of the Help and settings tab. */
enum class SettingsPage(val title: String) {
    MAIN("Help and settings"),
    SPEECH("Speech"),
    ANNOUNCEMENTS("Announcements and verbosity"),
    ROUTES("Routes"),
    UNITS("Units and directions"),
    ACCURACY("GPS accuracy"),
    GESTURES("Shake and What's Around"),
    MAPS("Offline maps"),
    REPORTS("Map reports and fixes"),
    GUIDE("User guide"),
}

/** Which tab is showing, and a place to start map browsing from. */
object AppNav {
    /** The page showing on the Help and settings tab. */
    val settingsPage = mutableStateOf(SettingsPage.MAIN)
    /** The single option open inside a settings page, by title, or null. */
    val settingsOption = mutableStateOf<String?>(null)
    /** A sub-screen of the Active route tab, by title, or null. */
    val routePage = mutableStateOf<String?>(null)
    /** True while route guidance runs: the first tab is then Active route instead of Explore. */
    var guiding: () -> Boolean = { false }

    /** True when the current screen is a sub-screen with somewhere to go up to. */
    val canGoUp: Boolean get() = (tab.value == Tab.HELP && (settingsPage.value != SettingsPage.MAIN || settingsOption.value != null)) ||
        (tab.value == Tab.EXPLORE && guiding() && routePage.value != null)

    /** Navigate up from a sub-screen. */
    fun up() {
        if (tab.value == Tab.EXPLORE) { routePage.value = null; return }
        if (tab.value != Tab.HELP) return
        if (settingsOption.value != null) settingsOption.value = null else settingsPage.value = SettingsPage.MAIN
    }

    fun openPage(p: SettingsPage) { settingsOption.value = null; settingsPage.value = p }

    /** The title for the top bar. */
    val title: String get() = when {
        tab.value == Tab.HELP -> settingsOption.value ?: settingsPage.value.title
        tab.value == Tab.EXPLORE && guiding() -> routePage.value ?: "Active route"
        else -> tab.value.label
    }

    /** The label for a tab at the bottom: the first is Active route while guiding. */
    fun label(t: Tab): String = if (t == Tab.EXPLORE && guiding()) "Active route" else t.label

    val tab = mutableStateOf(Tab.EXPLORE)
    val browseFrom = mutableStateOf<Place?>(null)
    /** When set, the report-a-map-problem dialog is showing. */
    val report = mutableStateOf<ReportRequest?>(null)
}
