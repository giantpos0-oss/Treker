package com.willwalsh.treker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** How a direction is spoken. */
enum class DirectionStyle(val label: String) {
    RELATIVE("Ahead, slightly to the left, to the left, behind you and so on"),
    CLOCK("Clock face, like 2 o'clock"),
}

/** How distances are spoken. */
enum class Units(val label: String) {
    FEET("Feet and miles"),
    YARDS("Yards and miles"),
    METERS("Meters and kilometers"),
}

/** How much Treker says for one kind of announcement. */
enum class Verbosity(val label: String) { BRIEF("Brief"), NORMAL("Normal"), DETAILED("Detailed") }

/** Driving routes: quickest or shortest. */
enum class RouteType(val label: String) { FASTEST("Fastest"), SHORTEST("Shortest") }

/** How hard a shake has to be to say where you are. */
enum class ShakeStrength(val label: String, val g: Float) { LIGHT("Light", 2.0f), MEDIUM("Medium", 2.6f), FIRM("Firm", 3.3f) }

/**
 * How precise your GPS position must be before Treker names streets, intersections, addresses and
 * places, or says you've arrived. Worse than this, Treker says the signal is weak and waits.
 */
enum class GpsAccuracy(val label: String, val meters: Float) {
    TIGHT("About 30 feet or 9 meters, most accurate", 9.2f),
    MEDIUM("About 50 feet or 15 meters", 15.3f),
    LOOSE("About 80 feet or 25 meters, works in more places", 25f),
}

/** How close to a walking destination Treker says you've arrived. */
enum class ArrivalDistance(val label: String, val meters: Double) {
    CLOSE("About 15 feet or 5 meters", 4.6),
    NORMAL("About 30 feet or 9 meters", 9.2),
    WIDE("About 50 feet or 15 meters", 15.3),
}

/** When to announce an intersection ahead, in meters. */
enum class AnnounceDistance(val label: String, val meters: Double) {
    CLOSE("Close, about 50 feet or 15 meters", 15.0),
    NORMAL("Normal, about 100 feet or 30 meters", 30.0),
    EARLY("Early, about 150 feet or 45 meters", 45.0),
    VERY_EARLY("Very early, about 200 feet or 60 meters", 60.0),
}

/** One piece of information live exploration can announce, each with its own on/off setting. */
enum class LiveItem(val key: String, val label: String, val defaultOn: Boolean) {
    COUNTRY("country", "Country", true),
    STATE("state", "State or province", true),
    COUNTY("county", "County", false),
    CITY("city", "City or town", true),
    NEIGHBORHOOD("neighborhood", "Neighborhood", false),
    ZIP("zip", "Zip code", true),
    STREET("street", "Street name", true),
    ADDRESS("address", "Nearest street address", false),
    INTERSECTIONS("intersections", "Approaching intersections", true),
    PLACES("places", "Places you pass", true),
}

/** Settings, saved on the phone. Compose reads them as state so screens update when they change. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val liveStates = LiveItem.entries.associateWith {
        mutableStateOf(prefs.getBoolean("live_${it.key}", it.defaultOn))
    }
    fun isOn(item: LiveItem) = liveStates.getValue(item).value
    fun set(item: LiveItem, on: Boolean) {
        liveStates.getValue(item).value = on
        prefs.edit().putBoolean("live_${item.key}", on).apply()
    }

    /** Live exploration runs all the time unless paused here. */
    var liveExploration by bool("live_exploration", true)
    var whatsAround by bool("whats_around", false)
    var units by enumPref("units", if (prefs.getBoolean("metric", false)) Units.METERS else Units.FEET)
    val metric: Boolean get() = units == Units.METERS
    var directionStyle by enumPref("direction_style", DirectionStyle.RELATIVE)

    // Speech
    /** Package of the preferred speech engine; empty uses the phone's default. */
    var speechEngine by string("speech_engine", "")
    /** Name of the preferred voice in that engine; empty uses the engine's default. */
    var speechVoice by string("speech_voice", "")
    var speechRate by float("speech_rate", 1.0f)
    var speechPitch by float("speech_pitch", 1.0f)
    var speechVolume by float("speech_volume", 1.0f)

    // Announcements
    var streetVerbosity by enumPref("verbosity_street", Verbosity.NORMAL)
    var intersectionVerbosity by enumPref("verbosity_intersection", Verbosity.NORMAL)
    var placeVerbosity by enumPref("verbosity_place", Verbosity.NORMAL)
    var areaVerbosity by enumPref("verbosity_area", Verbosity.NORMAL)
    var intersectionDistance by enumPref("intersection_distance", AnnounceDistance.NORMAL)

    // Accuracy
    var gpsAccuracy by enumPref("gps_accuracy", GpsAccuracy.TIGHT)
    var arrivalDistance by enumPref("arrival_distance", ArrivalDistance.NORMAL)

    /** Say when the GPS signal turns weak or good again. */
    var announceGps by bool("announce_gps", true)

    // Shake
    var shakeWhereAmI by bool("shake_where_am_i", true)
    var shakeStrength by enumPref("shake_strength", ShakeStrength.MEDIUM)

    // Routes
    var routeType by enumPref("route_type", RouteType.FASTEST)
    var avoidHighways by bool("avoid_highways", false)
    var avoidTolls by bool("avoid_tolls", false)
    var avoidFerries by bool("avoid_ferries", false)
    var avoidStairs by bool("avoid_stairs", true)
    var preferSidewalks by bool("prefer_sidewalks", true)
    var avoidAlleys by bool("avoid_alleys", true)
    var preferLitStreets by bool("prefer_lit", false)
    /** How far around you What's Around and Places you pass look, in meters. */
    var placesRadius by int("places_radius", 150)
    var vibrate by bool("vibrate", true)
    /** Practice mode: walking and driving guidance move along the route by themselves, for trying it at home. */
    var simulate by bool("simulate", false)

    private fun bool(key: String, def: Boolean) = Pref(mutableStateOf(prefs.getBoolean(key, def))) { prefs.edit().putBoolean(key, it).apply() }
    private fun float(key: String, def: Float) = Pref(mutableStateOf(prefs.getFloat(key, def))) { prefs.edit().putFloat(key, it).apply() }
    private fun string(key: String, def: String) = Pref(mutableStateOf(prefs.getString(key, def) ?: def)) { prefs.edit().putString(key, it).apply() }
    private fun int(key: String, def: Int) = Pref(mutableStateOf(prefs.getInt(key, def))) { prefs.edit().putInt(key, it).apply() }
    private fun <E : Enum<E>> enumPref(key: String, def: E): Pref<E> {
        val stored = prefs.getString(key, def.name)
        val value = def.declaringJavaClass.enumConstants?.firstOrNull { it.name == stored } ?: def
        return Pref(mutableStateOf(value)) { prefs.edit().putString(key, it.name).apply() }
    }

    class Pref<T>(private val state: androidx.compose.runtime.MutableState<T>, private val save: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = state.value
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            state.value = value; save(value)
        }
    }
}

val LocalSettings = staticCompositionLocalOf<Settings> { error("No settings") }
