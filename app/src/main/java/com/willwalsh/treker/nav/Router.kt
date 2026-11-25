package com.willwalsh.treker.nav

import android.content.Context
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import com.willwalsh.treker.data.LatLon
import com.willwalsh.treker.data.Offline
import com.willwalsh.treker.data.Regions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One instruction on a route: where on the route it happens and what to say. */
data class Maneuver(
    val text: String,
    /** Said before reaching the turn, e.g. "Turn left onto Main Street." */
    val verbalBefore: String,
    /** Said just after, e.g. "Continue for 500 feet." */
    val verbalAfter: String,
    val shapeIndex: Int,
    val lengthMeters: Double,
    val type: Int,
)

class Route(val points: List<LatLon>, val maneuvers: List<Maneuver>, val lengthMeters: Double, val seconds: Double)

/**
 * Offline routing with Valhalla, on the phone, using the downloaded region's routing tiles.
 * Walking uses Valhalla's pedestrian costing; motorized uses auto.
 */
class Router(private val context: Context) {
    private var engine: Valhalla? = null
    private var engineRegion: String? = null

    @Synchronized
    private fun engineFor(p: LatLon): Valhalla {
        val region = Offline.regionFor(p) ?: throw Exception("No offline map covers your location, so no route can be made. Download the map for this area in Help and settings.")
        if (engineRegion == region.id) engine?.let { return it }
        engine?.close()
        val tar = Regions.tilesFile(region.id)
        if (!tar.exists()) throw Exception("The routing part of the ${region.name} map is missing. Download the map again.")
        val config = ValhallaConfigFactory.usingTileExtract(tar.absolutePath)
        val manager = ValhallaConfigManager(context, ValhallaFile(context, "valhalla-${region.id}.json"))
        return Valhalla(context, config, manager).also { engine = it; engineRegion = region.id }
    }

    @Synchronized
    fun close() { engine?.close(); engine = null; engineRegion = null }

    suspend fun route(from: LatLon, to: LatLon, walking: Boolean, metric: Boolean, heading: Double? = null): Route =
        withContext(Dispatchers.IO) {
            val start = JSONObject().put("lat", from.lat).put("lon", from.lon)
            if (heading != null) start.put("heading", heading.toInt()).put("heading_tolerance", 60)
            val req = JSONObject()
                .put("locations", JSONArray().put(start).put(JSONObject().put("lat", to.lat).put("lon", to.lon)))
                .put("costing", if (walking) "pedestrian" else "auto")
                .put("units", if (metric) "kilometers" else "miles")
                .put("directions_options", JSONObject().put("language", "en-US"))
            val raw = try { engineFor(from).routeRaw(req.toString()) } catch (e: Exception) {
                throw Exception(friendly(e.message))
            }
            parse(JSONObject(raw))
        }

    private fun friendly(msg: String?): String = when {
        msg == null -> "Couldn't make a route."
        msg.contains("No path could be found", true) || msg.contains("442") -> "No route found between here and there."
        msg.contains("No suitable edges", true) || msg.contains("171") -> "Couldn't find a street or path near the start or the destination."
        msg.contains("exceeds", true) -> "That destination is too far for the offline map."
        else -> "Couldn't make a route: $msg"
    }

    private fun parse(o: JSONObject): Route {
        val trip = o.getJSONObject("trip")
        val points = ArrayList<LatLon>()
        val maneuvers = ArrayList<Maneuver>()
        val legs = trip.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val offset = points.size
            points.addAll(decodePolyline6(leg.getString("shape")))
            val ms = leg.getJSONArray("maneuvers")
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                val text = m.optString("instruction")
                maneuvers.add(Maneuver(
                    text = text,
                    verbalBefore = m.optString("verbal_pre_transition_instruction").ifBlank { text },
                    verbalAfter = m.optString("verbal_post_transition_instruction"),
                    shapeIndex = offset + m.optInt("begin_shape_index"),
                    lengthMeters = m.optDouble("length", 0.0) * (if (o.optJSONObject("trip")?.optString("units") == "kilometers") 1000.0 else 1609.344),
                    type = m.optInt("type"),
                ))
            }
        }
        val summary = trip.getJSONObject("summary")
        val unit = if (trip.optString("units") == "kilometers") 1000.0 else 1609.344
        return Route(points, maneuvers, summary.optDouble("length") * unit, summary.optDouble("time"))
    }

    companion object {
        /** Valhalla's shape encoding: Google polyline format with six decimal places. */
        fun decodePolyline6(s: String): List<LatLon> {
            val out = ArrayList<LatLon>()
            var i = 0; var lat = 0L; var lon = 0L
            while (i < s.length) {
                for (k in 0..1) {
                    var shift = 0; var result = 0L
                    while (true) {
                        val b = s[i++].code - 63
                        result = result or ((b and 0x1f).toLong() shl shift)
                        shift += 5
                        if (b < 0x20) break
                    }
                    val d = if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
                    if (k == 0) lat += d else lon += d
                }
                out.add(LatLon(lat / 1e6, lon / 1e6))
            }
            return out
        }
    }
}
