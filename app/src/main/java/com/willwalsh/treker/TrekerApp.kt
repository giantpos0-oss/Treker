package com.willwalsh.treker

import android.app.Application
import com.willwalsh.treker.browse.VirtualWalker
import com.willwalsh.treker.data.Favorites
import com.willwalsh.treker.data.Offline
import com.willwalsh.treker.explore.LiveExplorer
import com.willwalsh.treker.explore.WhatsAround
import com.willwalsh.treker.location.Compass
import com.willwalsh.treker.location.LocationHub
import com.willwalsh.treker.nav.Guidance
import com.willwalsh.treker.speech.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        Offline.init(this)
        speaker = Speaker(this).also { it.rate = settings.speechRate }
        location = LocationHub(this)
        compass = Compass(this)
        favorites = Favorites(this)
        explorer = LiveExplorer(this, scope)
        whatsAround = WhatsAround(this, scope)
        walker = VirtualWalker(this)
        guidance = Guidance(this, scope)
    }
}
