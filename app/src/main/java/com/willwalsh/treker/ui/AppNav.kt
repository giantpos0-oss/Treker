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

/** Which tab is showing, and a place to start map browsing from. */
object AppNav {
    val tab = mutableStateOf(Tab.EXPLORE)
    val browseFrom = mutableStateOf<Place?>(null)
    /** When set, the report-a-map-problem dialog is showing. */
    val report = mutableStateOf<ReportRequest?>(null)
}
