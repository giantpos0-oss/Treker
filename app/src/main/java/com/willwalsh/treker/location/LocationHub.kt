package com.willwalsh.treker.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import com.willwalsh.treker.data.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A GPS fix. [course] is the direction of travel when moving, or null. */
data class Fix(val pos: LatLon, val accuracy: Float, val speed: Float, val course: Double?, val time: Long)

/**
 * Smooths GPS positions: each new fix is blended with the running estimate, weighted by how accurate
 * each is, so one wild fix can't throw you across the street, while real movement (allowed for by
 * your speed) still comes straight through.
 */
class PositionFilter {
    private var lat = 0.0
    private var lon = 0.0
    private var variance = -1.0 // m², negative = no estimate yet
    private var lastTime = 0L

    fun reset() { variance = -1.0 }

    /** Returns the smoothed position and its accuracy in meters. */
    fun update(p: LatLon, accuracy: Float, speed: Float, timeMs: Long): Pair<LatLon, Float> {
        val acc = accuracy.toDouble().coerceAtLeast(1.0)
        val dt = (timeMs - lastTime) / 1000.0
        if (variance < 0 || dt > 10 || dt < 0) {
            lat = p.lat; lon = p.lon; variance = acc * acc; lastTime = timeMs
            return p to accuracy
        }
        if (dt > 0) {
            // How far you could have moved since the last fix, in meters per second.
            val q = 2.0 + speed
            variance += dt * q * q
            lastTime = timeMs
        }
        val k = variance / (variance + acc * acc)
        lat += k * (p.lat - lat)
        lon += k * (p.lon - lon)
        variance *= (1 - k)
        return LatLon(lat, lon) to Math.sqrt(variance).toFloat()
    }
}

/**
 * One shared stream of GPS fixes for the whole app, started by live exploration. Uses Android's
 * own location service (no Google services needed). On Android 12 and later it asks the fused
 * provider for its highest-accuracy mode and also listens to the GPS chip directly, which on phones
 * with dual-frequency GPS (like recent Pixels) is the most precise source; the more accurate of the
 * two wins. Fixes are smoothed by [PositionFilter].
 */
class LocationHub(context: Context) {
    private val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()
    private var running = false
    private val filter = PositionFilter()
    private var lastRawTime = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { accept(loc) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    private fun accept(loc: Location) {
        val prev = _fix.value
        if (loc.time < lastRawTime - 2000) return // stale fix arriving late
        // A much worse fix arriving soon after a good one is ignored.
        if (prev != null && loc.accuracy > prev.accuracy * 3 && loc.time - prev.time < 5000) return
        lastRawTime = maxOf(lastRawTime, loc.time)
        val (pos, _) = filter.update(LatLon(loc.latitude, loc.longitude), loc.accuracy, loc.speed, loc.time)
        // The accuracy reported is the GPS's own, not the smoothed estimate: smoothing steadies the
        // position but mustn't make Treker more confident than the satellites justify.
        _fix.value = Fix(pos, loc.accuracy, loc.speed,
            if (loc.hasBearing() && loc.speed > 0.6f) loc.bearing.toDouble() else prev?.course?.takeIf { loc.speed > 0.6f },
            loc.time)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        filter.reset()
        runCatching {
            val all = lm.allProviders
            if (Build.VERSION.SDK_INT >= 31) {
                val req = android.location.LocationRequest.Builder(1000L)
                    .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(500L)
                    .build()
                val exec = java.util.concurrent.Executor { r -> android.os.Handler(Looper.getMainLooper()).post(r) }
                for (p in listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)) {
                    if (p in all) lm.requestLocationUpdates(p, req, exec, listener)
                }
            } else {
                for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (p in all) lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                }
            }
            for (p in all) lm.getLastKnownLocation(p)?.let { if (_fix.value == null && System.currentTimeMillis() - it.time < 60_000) accept(it) }
        }.onFailure { running = false }
    }

    fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(listener)
    }
}

/**
 * Which way the top of the phone points, in compass degrees, from the rotation sensor.
 * Used by What's Around: point the phone to hear what's in that direction.
 */
class Compass(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _heading = MutableStateFlow<Double?>(null)
    val heading: StateFlow<Double?> = _heading.asStateFlow()
    val available get() = sensor != null
    private var users = 0
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    fun start() {
        users++
        if (users == 1 && sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { sm.unregisterListener(this); _heading.value = null }
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Held flat or upright, the direction is where the top edge of the phone points.
        val pitch = run {
            SensorManager.getOrientation(rot, orient)
            Math.toDegrees(orient[1].toDouble())
        }
        val m = if (pitch < -45) {
            // Phone held upright facing you, like taking a picture: use where the back of the phone points.
            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped); remapped
        } else rot
        SensorManager.getOrientation(m, orient)
        var az = Math.toDegrees(orient[0].toDouble())
        @Suppress("DEPRECATION")
        when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> az += 90
            Surface.ROTATION_180 -> az += 180
            Surface.ROTATION_270 -> az += 270
        }
        val h = (az + 360) % 360
        val prev = _heading.value
        // Light smoothing so the direction doesn't jitter.
        _heading.value = if (prev == null) h else (prev + com.willwalsh.treker.data.Geo.angleDiff(prev, h) * 0.25 + 360) % 360
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
