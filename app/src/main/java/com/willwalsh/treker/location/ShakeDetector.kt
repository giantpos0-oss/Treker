package com.willwalsh.treker.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Notices a deliberate shake of the phone: two hard jolts within about half a second.
 * Walking, running and a phone bouncing in a pocket stay well under the threshold.
 */
class ShakeDetector(context: Context, private val threshold: () -> Float, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var firstJolt = 0L
    private var lastShake = 0L
    private var running = false

    fun start() {
        if (running || sensor == null) return
        running = true
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        if (g < threshold()) return
        val now = System.currentTimeMillis()
        if (now - lastShake < 2500) return
        when {
            firstJolt == 0L || now - firstJolt > 700 -> firstJolt = now
            now - firstJolt > 120 -> { firstJolt = 0L; lastShake = now; onShake() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
