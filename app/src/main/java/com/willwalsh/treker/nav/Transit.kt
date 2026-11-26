package com.willwalsh.treker.nav

import com.willwalsh.treker.BuildConfig
import com.willwalsh.treker.data.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A stop along a transit ride. */
data class Stop(val name: String, val pos: LatLon)

/** One part of a transit trip: a walk, or a ride on one bus or train. */
class TransitLeg(
    val walking: Boolean,
    val from: Stop,
    val to: Stop,
    val instruction: String,
    val lineName: String = "",
    /** Stops the vehicle calls at between boarding and getting off. */
    val between: List<Stop> = emptyList(),
) {
    var boarded = false
    var stopsPassed = 0
    var warned = false
}

class TransitTrip(val legs: List<TransitLeg>, val summary: String) { var index = 0 }

/**
 * Public transit trips from Transitous (transitous.org), a free community service built on
 * open timetables (GTFS) and OpenStreetMap. Needs internet; walking parts are guided offline.
 */
object Transit {
    private const val BASE = "https://api.transitous.org/api"
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    suspend fun plan(from: LatLon, to: LatLon, units: com.willwalsh.treker.Units): TransitTrip? = withContext(Dispatchers.IO) {
        val q = "fromPlace=${from.lat},${from.lon}&toPlace=${to.lat},${to.lon}&detailedTransfers=false&maxItineraries=3"
        val json = try { get("$BASE/v6/plan?$q") } catch (e: NotFound) { get("$BASE/v5/plan?$q") }
        val its = json.optJSONArray("itineraries") ?: return@withContext null
        // The first itinerary that actually uses transit; otherwise the first one (walking only).
        var chosen: JSONObject? = null
        for (i in 0 until its.length()) {
            val itin = its.getJSONObject(i)
            val legs = itin.getJSONArray("legs")
            if ((0 until legs.length()).any { n -> legs.getJSONObject(n).optString("mode") != "WALK" }) { chosen = itin; break }
        }
        chosen = chosen ?: its.optJSONObject(0) ?: return@withContext null
        parse(chosen, units)
    }

    private fun parse(itin: JSONObject, units: com.willwalsh.treker.Units): TransitTrip {
        val legs = itin.getJSONArray("legs")
        val out = ArrayList<TransitLeg>()
        for (i in 0 until legs.length()) {
            val l = legs.getJSONObject(i)
            val mode = l.optString("mode")
            val from = stop(l.getJSONObject("from"))
            val to = stop(l.getJSONObject("to"))
            if (mode == "WALK") {
                val meters = l.optDouble("distance", Double.NaN)
                val dist = if (meters.isNaN()) "" else ", " + com.willwalsh.treker.data.Geo.distanceWords(meters, units)
                out.add(TransitLeg(true, from, to, "Walk to ${to.name}$dist."))
            } else {
                val vehicle = when (mode) {
                    "BUS", "COACH" -> "bus"
                    "TRAM" -> "streetcar"
                    "SUBWAY", "METRO" -> "subway"
                    "FERRY" -> "ferry"
                    "RAIL", "REGIONAL_RAIL", "SUBURBAN", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL" -> "train"
                    else -> "transit"
                }
                val short = l.optString("routeShortName").ifBlank { l.optString("displayName") }.ifBlank { l.optString("routeLongName") }
                val lineName = "$vehicle $short".trim()
                val headsign = l.optString("headsign")
                val between = l.optJSONArray("intermediateStops")?.let { arr -> List(arr.length()) { k -> stop(arr.getJSONObject(k)) } } ?: emptyList()
                val departs = time(l.optString("startTime"))
                val track = l.getJSONObject("from").optString("track")
                val text = buildString {
                    append("At ${from.name}, take the $lineName")
                    if (headsign.isNotBlank()) append(" toward $headsign")
                    if (departs != null) append(", leaving at $departs")
                    if (track.isNotBlank()) append(" from track $track")
                    val rides = between.size + 1
                    append(". Ride ${if (rides == 1) "1 stop" else "$rides stops"} and get off at ${to.name}.")
                }
                out.add(TransitLeg(false, from, to, text, lineName, between))
            }
        }
        val minutes = itin.optLong("duration") / 60
        val rides = out.count { leg -> !leg.walking }
        val transfers = itin.optInt("transfers", 0)
        val summary = buildString {
            append("about ${if (minutes >= 60) "${minutes / 60} hours ${minutes % 60} minutes" else "$minutes minutes"}")
            append(if (rides == 0) ", walking only." else if (rides == 1) ", 1 ride." else ", $rides rides with $transfers transfers.")
            time(itin.optString("endTime"))?.let { t -> append(" Arriving at $t.") }
        }
        return TransitTrip(out, summary)
    }

    private fun stop(o: JSONObject) = Stop(o.optString("name").ifBlank { "the stop" }, LatLon(o.getDouble("lat"), o.getDouble("lon")))

    private fun time(iso: String): String? = runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(clock)
    }.getOrNull()

    private class NotFound : Exception()

    private fun get(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            // Transitous asks every app to identify itself with a way to reach the developer.
            setRequestProperty("User-Agent", "Treker/${BuildConfig.VERSION_NAME} (Android; https://github.com/giantpos0-oss/Treker)")
        }
        val code = c.responseCode
        if (code == 404) throw NotFound()
        if (code !in 200..299) throw Exception("The transit service didn't answer (error $code). Try again in a moment.")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }
}
