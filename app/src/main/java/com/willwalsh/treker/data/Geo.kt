package com.willwalsh.treker.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the earth. */
data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val R = 6_371_000.0

    /** Distance in meters. */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Compass bearing from a to b, 0 to 360 degrees, 0 = north. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** The point [meters] away from [from] in direction [bearingDeg]. */
    fun move(from: LatLon, bearingDeg: Double, meters: Double): LatLon {
        val d = meters / R
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(from.lat); val l1 = Math.toRadians(from.lon)
        val p2 = Math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), Math.toDegrees(l2))
    }

    /** Smallest signed angle from a to b, -180 to 180. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    private val compass16 = listOf(
        "north", "north-northeast", "northeast", "east-northeast", "east", "east-southeast", "southeast",
        "south-southeast", "south", "south-southwest", "southwest", "west-southwest", "west",
        "west-northwest", "northwest", "north-northwest",
    )
    private val compass8 = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")

    fun compassWord(bearing: Double, fine: Boolean = false): String =
        if (fine) compass16[(((bearing % 360) + 360) % 360 / 22.5).roundToInt() % 16]
        else compass8[(((bearing % 360) + 360) % 360 / 45.0).roundToInt() % 8]

    /**
     * Where [target] is relative to the way you're facing ([heading]), in plain words:
     * straight ahead within 15 degrees either side; "ahead and slightly to the left" up to 60;
     * "to the left" up to 120; "behind you to the left" up to 165; then "behind you".
     */
    fun relativeWord(heading: Double, target: Double): String {
        val d = angleDiff(heading, target) // negative is left, positive is right
        val side = if (d < 0) "left" else "right"
        val a = Math.abs(d)
        return when {
            a <= 15 -> "ahead"
            a <= 60 -> "ahead and slightly to the $side"
            a <= 120 -> "to the $side"
            a <= 165 -> "behind you to the $side"
            else -> "behind you"
        }
    }

    /** "2 o'clock" style direction of [target] relative to facing [heading]. */
    fun clockWord(heading: Double, target: Double): String {
        val rel = ((target - heading) % 360 + 360) % 360
        var h = (rel / 30.0).roundToInt() % 12
        if (h == 0) h = 12
        return "$h o'clock"
    }

    /** Distance in words: feet and miles, yards and miles, or meters and kilometers. */
    fun distanceWords(meters: Double, units: com.willwalsh.treker.Units): String {
        when (units) {
            com.willwalsh.treker.Units.METERS -> {
                if (meters < 1000) return "${roundNice(meters)} meters"
                val tenths = (meters / 100.0).roundToInt()
                return when {
                    tenths == 10 -> "1 kilometer"
                    tenths % 10 == 0 -> "${tenths / 10} kilometers"
                    else -> "${tenths / 10}.${tenths % 10} kilometers"
                }
            }
            com.willwalsh.treker.Units.YARDS -> {
                val yards = meters * 1.09361
                if (yards < 1000) return "${roundNice(yards)} yards"
            }
            com.willwalsh.treker.Units.FEET -> {
                val feet = meters * 3.28084
                if (feet < 1000) return "${roundNice(feet)} feet"
            }
        }
        val miles = meters / 1609.344
        // "0.2 miles", "1 mile", "1.5 miles", "12 miles".
        if (miles >= 10) return "${miles.roundToInt()} miles"
        val tenths = (miles * 10).roundToInt()
        return when {
            tenths == 10 -> "1 mile"
            tenths % 10 == 0 -> "${tenths / 10} miles"
            else -> "${tenths / 10}.${tenths % 10} miles"
        }
    }

    private fun roundNice(v: Double): Int = when {
        v < 20 -> v.roundToInt()
        v < 100 -> (v / 5).roundToInt() * 5
        else -> (v / 10).roundToInt() * 10
    }
}
