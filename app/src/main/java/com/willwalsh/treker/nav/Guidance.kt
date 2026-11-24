package com.willwalsh.treker.nav

import android.app.Activity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.google.android.libraries.navigation.AudioGuidanceSettings
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Waypoint
import com.willwalsh.treker.TrekerApp
import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.GooglePlaces
import com.willwalsh.treker.data.LatLon
import com.willwalsh.treker.data.Place
import com.willwalsh.treker.location.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class TravelMode(val label: String) { PEDESTRIAN("Pedestrian"), MOTORIZED("Motorized"), TRANSIT("Public transit") }

/**
 * Turn-by-turn guidance. Pedestrian and motorized use Google's Navigation SDK (route following,
 * rerouting and spoken turns). Public transit uses Google's Routes API with Treker's own guidance,
 * because the Navigation SDK doesn't do transit.
 */
class Guidance(private val app: TrekerApp, private val scope: CoroutineScope) {
    val active = mutableStateOf(false)
    val mode = mutableStateOf<TravelMode?>(null)
    val destination = mutableStateOf<Place?>(null)
    /** The instruction you're working toward now, as shown and spoken. */
    val current = mutableStateOf("")
    val status = mutableStateOf("")
    /** All the steps of the route, for reviewing the whole trip. */
    val steps = mutableStateListOf<String>()

    private var navigator: Navigator? = null
    private var transit: TransitTrip? = null
    private var transitJob: Job? = null
    private val metric get() = app.settings.metric

    private fun say(text: String, interrupt: Boolean = true) = app.speaker.say(text, interrupt)

    fun start(activity: Activity, place: Place, travel: TravelMode) {
        stop(silent = true)
        if (!GooglePlaces.hasKey) {
            say("Navigation needs Treker's Google key, which isn't set up in this build yet.")
            return
        }
        destination.value = place; mode.value = travel; active.value = true
        steps.clear(); current.value = ""
        status.value = "Getting a ${travel.label.lowercase()} route to ${place.name}."
        say(status.value)
        when (travel) {
            TravelMode.TRANSIT -> startTransit(place)
            else -> startNavSdk(activity, place, travel)
        }
    }

    // ---- Pedestrian and motorized: Google's Navigation SDK ----

    private fun startNavSdk(activity: Activity, place: Place, travel: TravelMode) {
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                if (!active.value) return
                navigator = nav
                val wp = runCatching {
                    if (place.placeId != null) Waypoint.builder().setPlaceIdString(place.placeId).setTitle(place.name).build()
                    else null
                }.getOrNull() ?: Waypoint.builder().setLatLng(place.pos.lat, place.pos.lon).setTitle(place.name).build()
                val options = RoutingOptions().travelMode(
                    if (travel == TravelMode.PEDESTRIAN) RoutingOptions.TravelMode.WALKING else RoutingOptions.TravelMode.DRIVING
                )
                nav.setAudioGuidanceSettings(
                    AudioGuidanceSettings.builder()
                        .setGuidanceMode(AudioGuidanceSettings.GuidanceMode.VOICE_ALERTS_AND_GUIDANCE)
                        .build()
                )
                nav.registerServiceForNavUpdates(app.packageName, NavUpdatesService::class.java.name, 30)
                nav.addArrivalListener { _ ->
                    say("Arrived at ${place.name}.")
                    status.value = "Arrived at ${place.name}."
                    stop(silent = true)
                }
                nav.setDestination(wp, options).setOnResultListener { routeStatus ->
                    if (routeStatus == Navigator.RouteStatus.OK) {
                        status.value = "Guiding you to ${place.name}."
                        if (app.settings.simulate) {
                            nav.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(if (travel == TravelMode.PEDESTRIAN) 1f else 2f))
                        }
                        nav.startGuidance()
                    } else {
                        status.value = routeProblem(routeStatus)
                        say(status.value)
                        stop(silent = true)
                    }
                }
            }

            override fun onError(errorCode: Int) {
                status.value = when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> "Google didn't accept Treker's key for navigation. The Navigation SDK may not be turned on for it."
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "Google's navigation terms need to be accepted before guidance can start."
                    NavigationApi.ErrorCode.NETWORK_ERROR -> "No internet connection, so navigation couldn't start."
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "Treker needs location permission to navigate."
                    else -> "Navigation couldn't start, error $errorCode."
                }
                say(status.value)
                active.value = false
            }
        })
    }

    private fun routeProblem(s: Navigator.RouteStatus): String = when (s) {
        Navigator.RouteStatus.NO_ROUTE_FOUND -> "No route found to ${destination.value?.name}."
        Navigator.RouteStatus.NETWORK_ERROR -> "No internet connection, so no route could be found."
        Navigator.RouteStatus.QUOTA_CHECK_FAILED -> "Treker's Google navigation allowance is used up for now."
        Navigator.RouteStatus.LOCATION_DISABLED, Navigator.RouteStatus.LOCATION_UNKNOWN -> "Your location isn't known yet. Wait for GPS and try again."
        else -> "Couldn't get a route: $s."
    }

    /** Called by [NavUpdatesService] with each turn-by-turn update from the Navigation SDK. */
    fun onNavStep(instruction: String?, metersToStep: Int?, upcoming: List<String>) {
        if (instruction.isNullOrBlank()) return
        current.value = if (metersToStep != null) "In ${Geo.distanceWords(metersToStep.toDouble(), metric)}, $instruction" else instruction
        steps.clear(); steps.add(current.value); steps.addAll(upcoming)
    }

    // ---- Public transit: Routes API plus Treker's own guidance ----

    private fun startTransit(place: Place) {
        val from = app.location.fix.value?.pos
        if (from == null) { status.value = "Your location isn't known yet. Wait for GPS and try again."; say(status.value); active.value = false; return }
        transitJob = scope.launch {
            val trip = try { TransitTrip.plan(from, place, metric) } catch (e: Exception) {
                status.value = e.message ?: "Couldn't plan a transit trip."; say(status.value); active.value = false; return@launch
            }
            if (trip == null || trip.legs.isEmpty()) {
                status.value = "No public transit route found to ${place.name}."; say(status.value); active.value = false; return@launch
            }
            transit = trip
            steps.clear(); steps.addAll(trip.legs.map { it.instruction })
            status.value = "Transit trip to ${place.name}: ${trip.summary}"
            say(status.value)
            announceTransitLeg()
            app.location.fix.collect { f -> if (f != null && active.value) onTransitFix(f) }
        }
    }

    private fun announceTransitLeg() {
        val t = transit ?: return
        val leg = t.legs.getOrNull(t.index) ?: return
        current.value = leg.instruction
        say(leg.instruction, interrupt = false)
    }

    private fun onTransitFix(f: Fix) {
        val t = transit ?: return
        val leg = t.legs.getOrNull(t.index) ?: return
        val toEnd = Geo.distance(f.pos, leg.end)
        if (leg.isTransit) {
            val fromStart = Geo.distance(f.pos, leg.start)
            if (!leg.boarded && fromStart > 150) { leg.boarded = true; say("Riding ${leg.lineName}. I'll tell you when your stop is coming up.") }
            if (leg.boarded && !leg.warned && toEnd < 500) { leg.warned = true; say("Your stop, ${leg.endName}, is coming up. Get ready.") }
            if (leg.boarded && toEnd < 90) { say("Get off now at ${leg.endName}."); next() }
        } else {
            if (toEnd < 20) next()
            else current.value = "${leg.instruction} ${Geo.distanceWords(toEnd, metric)} to go."
        }
    }

    private fun next() {
        val t = transit ?: return
        t.index++
        if (t.index >= t.legs.size) {
            say("Arrived at ${destination.value?.name}.")
            status.value = "Arrived at ${destination.value?.name}."
            stop(silent = true)
        } else announceTransitLeg()
    }

    /** Repeat the current instruction. */
    fun repeat() {
        val text = current.value.ifBlank { status.value }
        say(text.ifBlank { "No guidance running." })
    }

    fun stop(silent: Boolean = false) {
        val wasActive = active.value
        active.value = false
        navigator?.let { nav ->
            runCatching { nav.stopGuidance(); nav.clearDestinations(); nav.simulator.unsetUserLocation() }
        }
        navigator = null
        transitJob?.cancel(); transitJob = null
        transit = null
        if (wasActive && !silent) say("Guidance stopped.")
    }
}

/** A planned public transit trip from the Routes API, broken into walking and riding legs. */
class TransitTrip(val legs: List<Leg>, val summary: String) {
    var index = 0

    class Leg(
        val instruction: String, val start: LatLon, val end: LatLon,
        val isTransit: Boolean, val lineName: String = "", val endName: String = "",
    ) {
        var boarded = false
        var warned = false
    }

    companion object {
        suspend fun plan(from: LatLon, to: Place, metric: Boolean): TransitTrip? {
            fun loc(p: LatLon) = JSONObject().put("location", JSONObject().put("latLng",
                JSONObject().put("latitude", p.lat).put("longitude", p.lon)))
            val body = JSONObject()
                .put("origin", loc(from))
                .put("destination", if (to.placeId != null) JSONObject().put("placeId", to.placeId) else loc(to.pos))
                .put("travelMode", "TRANSIT")
                .put("units", if (metric) "METRIC" else "IMPERIAL")
            val res = GooglePlaces.post(
                "https://routes.googleapis.com/directions/v2:computeRoutes", body,
                "routes.duration,routes.distanceMeters,routes.localizedValues," +
                    "routes.legs.steps.travelMode,routes.legs.steps.distanceMeters,routes.legs.steps.navigationInstruction," +
                    "routes.legs.steps.startLocation,routes.legs.steps.endLocation,routes.legs.steps.transitDetails",
            )
            val route = res.optJSONArray("routes")?.optJSONObject(0) ?: return null
            val out = ArrayList<Leg>()
            val legs = route.optJSONArray("legs") ?: return null
            for (l in 0 until legs.length()) {
                val st = legs.getJSONObject(l).optJSONArray("steps") ?: continue
                for (s in 0 until st.length()) {
                    val step = st.getJSONObject(s)
                    val start = latLng(step.optJSONObject("startLocation")) ?: continue
                    val end = latLng(step.optJSONObject("endLocation")) ?: continue
                    val td = step.optJSONObject("transitDetails")
                    if (step.optString("travelMode") == "TRANSIT" && td != null) {
                        val sd = td.optJSONObject("stopDetails")
                        val dep = sd?.optJSONObject("departureStop")?.optString("name").orEmpty()
                        val arr = sd?.optJSONObject("arrivalStop")?.optString("name").orEmpty()
                        val line = td.optJSONObject("transitLine")
                        val vehicle = line?.optJSONObject("vehicle")?.optJSONObject("name")?.optString("text").orEmpty().ifBlank { "Transit" }
                        val lineName = "$vehicle ${line?.optString("nameShort").orEmpty().ifBlank { line?.optString("name").orEmpty() }}".trim()
                        val headsign = td.optString("headsign")
                        val time = td.optJSONObject("localizedValues")?.optJSONObject("departureTime")
                            ?.optJSONObject("time")?.optString("text").orEmpty()
                        val stops = td.optInt("stopCount", 0)
                        val text = buildString {
                            append("At $dep, take $lineName")
                            if (headsign.isNotBlank()) append(" toward $headsign")
                            if (time.isNotBlank()) append(", leaving at $time")
                            append(". Ride ")
                            append(if (stops == 1) "1 stop" else "$stops stops")
                            append(" and get off at $arr.")
                        }
                        out.add(Leg(text, start, end, true, lineName, arr))
                    } else {
                        val instr = step.optJSONObject("navigationInstruction")?.optString("instructions").orEmpty()
                        val dist = step.optInt("distanceMeters", 0)
                        if (instr.isBlank() && dist < 5) continue
                        out.add(Leg("${instr.ifBlank { "Walk" }}, ${Geo.distanceWords(dist.toDouble(), metric)}.", start, end, false))
                    }
                }
            }
            val lv = route.optJSONObject("localizedValues")
            val duration = lv?.optJSONObject("duration")?.optString("text").orEmpty()
            val rides = out.count { it.isTransit }
            val summary = buildString {
                if (duration.isNotBlank()) append("about $duration, ")
                append(if (rides == 1) "1 ride." else "$rides rides.")
            }
            return TransitTrip(out, summary)
        }

        private fun latLng(o: JSONObject?): LatLon? {
            val ll = o?.optJSONObject("latLng") ?: return null
            return LatLon(ll.optDouble("latitude"), ll.optDouble("longitude"))
        }
    }
}
