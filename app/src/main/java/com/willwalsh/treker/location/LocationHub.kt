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
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.willwalsh.treker.data.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A GPS fix. [course] is the direction of travel when moving, or null. */
data class Fix(val pos: LatLon, val accuracy: Float, val speed: Float, val course: Double?, val time: Long)

/** One shared stream of GPS fixes for the whole app, started by live exploration. */
class LocationHub(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _fix.value = it.toFix() }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        runCatching {
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            client.lastLocation.addOnSuccessListener { loc -> if (loc != null && _fix.value == null) _fix.value = loc.toFix() }
        }.onFailure { running = false }
    }

    fun stop() {
        if (!running) return
        running = false
        client.removeLocationUpdates(callback)
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
