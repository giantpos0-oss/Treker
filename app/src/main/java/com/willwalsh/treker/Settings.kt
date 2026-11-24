package com.willwalsh.treker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** How a direction is spoken. */
enum class DirectionStyle(val label: String) { CLOCK("Clock face"), COMPASS("Compass") }

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
    HEADING("heading", "Direction of travel", false),
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
    var metric by bool("metric", false)
    var speechRate by float("speech_rate", 1.0f)
    var directionStyle by enumPref("direction_style", DirectionStyle.CLOCK)
    /** How far around you What's Around and Places you pass look, in meters. */
    var placesRadius by int("places_radius", 150)
    var vibrate by bool("vibrate", true)
    /** Practice mode: walking and driving guidance move along the route by themselves, for trying it at home. */
    var simulate by bool("simulate", false)

    private fun bool(key: String, def: Boolean) = Pref(mutableStateOf(prefs.getBoolean(key, def))) { prefs.edit().putBoolean(key, it).apply() }
    private fun float(key: String, def: Float) = Pref(mutableStateOf(prefs.getFloat(key, def))) { prefs.edit().putFloat(key, it).apply() }
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
