package com.willwalsh.treker.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.willwalsh.treker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/** A place: a business, landmark, address, favorite or search result. */
data class Place(
    val name: String,
    val pos: LatLon,
    val address: String = "",
    val kind: String = "",
    val placeId: String? = null,
) {
    fun toJson() = JSONObject().put("name", name).put("lat", pos.lat).put("lon", pos.lon)
        .put("address", address).put("kind", kind).put("placeId", placeId ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Place(
            o.getString("name"), LatLon(o.getDouble("lat"), o.getDouble("lon")),
            o.optString("address"), o.optString("kind"), o.optString("placeId").ifBlank { null },
        )
    }
}

class GoogleKeyMissing : Exception(
    "Treker's Google key isn't set up yet, so Google places, search and navigation aren't available in this build."
)

/** Google Places API (New). Every call is billed to Treker's key, so results are cached. */
object GooglePlaces {
    val hasKey get() = BuildConfig.GOOGLE_KEY.isNotBlank()
    private val lock = Mutex()

    /** Nearby places, cached by area so turning around or walking back costs nothing. */
    private class Tile(val center: LatLon, val radius: Double, val places: List<Place>, val time: Long)
    private val tiles = ArrayList<Tile>()
    private const val TILE_MAX_AGE = 24L * 3600_000L

    suspend fun nearby(p: LatLon, radius: Double): List<Place> = lock.withLock {
        val now = System.currentTimeMillis()
        tiles.removeAll { now - it.time > TILE_MAX_AGE }
        val cached = tiles.filter { Geo.distance(it.center, p) < it.radius * 0.5 }
        if (cached.isNotEmpty()) return cached.flatMap { it.places }.distinctBy { it.placeId ?: it.name }
        if (!hasKey) throw GoogleKeyMissing()
        val body = JSONObject()
            .put("maxResultCount", 20)
            .put("rankPreference", "DISTANCE")
            .put("locationRestriction", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", p.lat).put("longitude", p.lon))
                .put("radius", radius.coerceIn(30.0, 2000.0))))
        val res = post("https://places.googleapis.com/v1/places:searchNearby", body,
            "places.id,places.displayName,places.location,places.shortFormattedAddress,places.primaryTypeDisplayName")
        val list = parse(res)
        tiles.add(0, Tile(p, radius, list, now))
        while (tiles.size > 40) tiles.removeAt(tiles.size - 1)
        list
    }

    /** Text search: an address, a business name, or "coffee". Biased toward where you are. */
    suspend fun search(query: String, near: LatLon?): List<Place> {
        if (!hasKey) throw GoogleKeyMissing()
        val body = JSONObject().put("textQuery", query).put("pageSize", 20)
        if (near != null) body.put("locationBias", JSONObject().put("circle", JSONObject()
            .put("center", JSONObject().put("latitude", near.lat).put("longitude", near.lon))
            .put("radius", 30_000.0)))
        val res = post("https://places.googleapis.com/v1/places:searchText", body,
            "places.id,places.displayName,places.location,places.formattedAddress,places.primaryTypeDisplayName")
        return parse(res)
    }

    private fun parse(res: JSONObject): List<Place> {
        val arr = res.optJSONArray("places") ?: JSONArray()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val loc = o.getJSONObject("location")
            Place(
                name = o.optJSONObject("displayName")?.optString("text").orEmpty().ifBlank { "Unnamed place" },
                pos = LatLon(loc.getDouble("latitude"), loc.getDouble("longitude")),
                address = o.optString("formattedAddress").ifBlank { o.optString("shortFormattedAddress") },
                kind = o.optJSONObject("primaryTypeDisplayName")?.optString("text").orEmpty(),
                placeId = o.optString("id").ifBlank { null },
            )
        }
    }

    suspend fun post(url: String, body: JSONObject, fieldMask: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Goog-Api-Key", BuildConfig.GOOGLE_KEY)
            setRequestProperty("X-Goog-FieldMask", fieldMask)
            setRequestProperty("X-Android-Package", BuildConfig.APPLICATION_ID)
            SigningCert.sha1?.let { setRequestProperty("X-Android-Cert", it) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("error $code")
            throw Exception("Google said: $msg")
        }
        JSONObject(text)
    }
}

/**
 * The signing certificate fingerprint, sent with Google requests so a key restricted to Treker
 * (package name plus certificate) accepts them.
 */
object SigningCert {
    var sha1: String? = null
        private set

    fun load(context: Context) {
        sha1 = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val sigs = if (Build.VERSION.SDK_INT >= 28)
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            else pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
            val cert = sigs?.firstOrNull() ?: return@runCatching null
            java.security.MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
                .joinToString("") { "%02X".format(it) }
        }.getOrNull()
    }
}

/** What the phone's own address lookup says about a spot: free, no Google key needed. */
data class AreaInfo(
    val country: String?, val state: String?, val county: String?, val city: String?,
    val neighborhood: String?, val zip: String?, val street: String?, val address: String?,
)

object AddressLookup {
    suspend fun at(context: Context, p: LatLon): AreaInfo? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val a: Address = (if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine<List<Address>?> { cont ->
                geocoder.getFromLocation(p.lat, p.lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                })
            }
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocation(p.lat, p.lon, 1) }.getOrNull()
        })?.firstOrNull() ?: return null
        val number = a.subThoroughfare
        val street = a.thoroughfare
        return AreaInfo(
            country = a.countryName,
            state = a.adminArea,
            county = a.subAdminArea,
            city = a.locality,
            neighborhood = a.subLocality,
            zip = a.postalCode,
            street = street,
            address = if (number != null && street != null) "$number $street" else a.getAddressLine(0),
        )
    }

    /** An address typed in one field, looked up by the phone (fallback when there's no Google key). */
    suspend fun search(context: Context, text: String): List<Place> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        val list: List<Address> = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(text, 10, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                })
            }
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocationName(text, 10) }.getOrNull().orEmpty()
        }
        return list.map {
            val line = it.getAddressLine(0) ?: text
            Place(line.substringBefore(","), LatLon(it.latitude, it.longitude), line, "Address")
        }
    }
}
