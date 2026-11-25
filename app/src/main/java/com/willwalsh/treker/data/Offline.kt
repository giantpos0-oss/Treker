package com.willwalsh.treker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class NoMapForHere : Exception("No offline map covers this spot. Download the map for this area in Help and settings, under Offline maps.")

/**
 * Treker's offline map: every downloaded region, opened once and kept open.
 * All lookups run off the main thread.
 */
object Offline {
    private val regions = ArrayList<RegionDb>()
    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.filesDir, "maps").apply { mkdirs() }
        reload()
    }

    val mapsDir: File get() = dir

    @Synchronized
    fun reload() {
        regions.forEach { runCatching { it.close() } }
        regions.clear()
        dir.listFiles { f -> f.name.endsWith(".db") }?.forEach { f ->
            runCatching { RegionDb(f) }.onSuccess { regions.add(it) }
        }
    }

    @Synchronized
    fun close(id: String) {
        regions.filter { it.id == id }.forEach { runCatching { it.close() } }
        regions.removeAll { it.id == id }
    }

    val installed: List<RegionDb> @Synchronized get() = regions.toList()

    /** The smallest downloaded region containing [p]. */
    @Synchronized
    fun regionFor(p: LatLon): RegionDb? = regions.filter { it.covers(p) }.minByOrNull { it.boxSize }

    fun hasMapFor(p: LatLon) = regionFor(p) != null

    suspend fun streets(p: LatLon, radius: Double): StreetArea? = withContext(Dispatchers.IO) {
        val area = regionFor(p)?.let { runCatching { it.streetArea(p, radius) }.getOrNull() } ?: return@withContext null
        if (Corrections.items.none { it.type == MapCorrection.Type.RENAME_STREET }) return@withContext area
        // Street names you corrected on your phone take effect straight away.
        StreetArea(area.center, area.radius, area.ways.map { w ->
            val at = w.nodes.firstNotNullOfOrNull { area.nodes[it] } ?: p
            w.copy(name = Corrections.streetName(w.name, at))
        }, area.nodes)
    }

    suspend fun pois(p: LatLon, radius: Double): List<Place> = withContext(Dispatchers.IO) {
        val r = regionFor(p) ?: throw NoMapForHere()
        Corrections.applyToPlaces(r.pois(p, radius), p, radius)
    }

    suspend fun search(query: String, near: LatLon?): List<Place> = withContext(Dispatchers.IO) {
        val list = (near?.let { regionFor(it) }?.let { listOf(it) } ?: installed)
        if (list.isEmpty()) throw NoMapForHere()
        val found = list.flatMap { runCatching { it.search(query, near) }.getOrDefault(emptyList()) } + Corrections.searchAdded(query)
        Corrections.applyToPlaces(found, null, 0.0)
            .let { all -> if (near != null) all.sortedBy { Geo.distance(near, it.pos) } else all }
            .take(50)
    }

    /** Where [p] is, from boundaries, the nearest address and the nearest street. */
    suspend fun areaInfo(p: LatLon, streetsHere: StreetArea? = null, course: Double? = null): AreaInfo? = withContext(Dispatchers.IO) {
        val r = regionFor(p) ?: return@withContext null
        val areas = runCatching { r.areasAt(p) }.getOrDefault(emptyMap())
        val street = (streetsHere ?: runCatching { r.streetArea(p, 150.0) }.getOrNull())
            ?.nearestStreet(p, course)?.takeIf { it.second < 30 }?.first?.name
        val addr = runCatching { r.nearestAddress(p, street = street) }.getOrNull()
        AreaInfo(
            country = areas["country"],
            state = areas["state"],
            county = areas["county"],
            city = areas["city"] ?: addr?.second,
            neighborhood = areas["neighborhood"],
            zip = areas["zip"] ?: addr?.third,
            street = street,
            address = addr?.first,
        )
    }
}
