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
 * One shared stream of GPS fixes for the whole app, started by live exploration. Uses Android's
 * own location service (no Google services needed): its fused provider on Android 12 and later,
 * plain GPS before that.
 */
class LocationHub(context: Context) {
    private val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { accept(loc) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    private fun accept(loc: Location) {
        val prev = _fix.value
        // Ignore a much worse fix arriving soon after a good one.
        if (prev != null && loc.accuracy > prev.accuracy * 3 && loc.time - prev.time < 5000) return
        _fix.value = loc.toFix()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            else {
                if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
        }
        runCatching {
            for (p in providers) {
                lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { if (_fix.value == null) accept(it) }
            }
        }.onFailure { running = false }
    }

    fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(listener)
    }

    private fun Location.toFix() = Fix(
        LatLon(latitude, longitude), accuracy, speed,
        if (hasBearing() && speed > 0.6f) bearing.toDouble() else null, time,
    )
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
