package com.willwalsh.treker

import android.app.Application
import com.willwalsh.treker.browse.VirtualWalker
import com.willwalsh.treker.data.Favorites
import com.willwalsh.treker.data.Corrections
import com.willwalsh.treker.data.Offline
import com.willwalsh.treker.data.Reports
import com.willwalsh.treker.explore.LiveExplorer
import com.willwalsh.treker.explore.WhatsAround
import com.willwalsh.treker.location.Compass
import com.willwalsh.treker.location.LocationHub
import com.willwalsh.treker.nav.Guidance
import com.willwalsh.treker.speech.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Everything that lives as long as the app: speech, GPS, compass, and the explorers. */
class TrekerApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var settings: Settings
    lateinit var speaker: Speaker
    lateinit var location: LocationHub
    lateinit var compass: Compass
    lateinit var favorites: Favorites
    lateinit var explorer: LiveExplorer
    lateinit var whatsAround: WhatsAround
    lateinit var walker: VirtualWalker
    lateinit var guidance: Guidance

    /** Pause or resume live exploration and route guidance together. */
    fun togglePause() {
        settings.liveExploration = !settings.liveExploration
        if (!settings.liveExploration) {
            speaker.stop()
            speaker.say("Paused.", true)
        } else {
            speaker.say("Resumed.", true)
            // Back on a route: say where things stand straight away.
            if (guidance.active.value) guidance.repeat()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        // Pause lasts until you resume or Treker restarts; it's never left on by accident.
        settings.liveExploration = true
        Offline.init(this)
        Corrections.init(this)
        Reports.init(this)
        speaker = Speaker(this, settings)
        location = LocationHub(this)
        compass = Compass(this)
        favorites = Favorites(this)
        explorer = LiveExplorer(this, scope)
        whatsAround = WhatsAround(this, scope)
        walker = VirtualWalker(this)
        guidance = Guidance(this, scope)
        com.willwalsh.treker.ui.AppNav.guiding = { guidance.active.value }
        // Reports made without internet go out the next time Treker starts.
        if (Reports.waiting > 0) scope.launch { Reports.sendWaiting() }
    }
}
