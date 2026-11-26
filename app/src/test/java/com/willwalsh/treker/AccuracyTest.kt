package com.willwalsh.treker

import com.willwalsh.treker.data.Geo
import com.willwalsh.treker.data.LatLon
import com.willwalsh.treker.data.RegionDb
import com.willwalsh.treker.data.StreetArea
import com.willwalsh.treker.data.Way
import com.willwalsh.treker.location.Fix
import com.willwalsh.treker.location.PositionFilter
import com.willwalsh.treker.nav.Maneuver
import com.willwalsh.treker.nav.Route
import com.willwalsh.treker.nav.RouteFollower
import com.willwalsh.treker.nav.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the math everything Treker says depends on: distances, directions, which street you're
 * on, which intersection is which, and when turns are announced.
 */
class AccuracyTest {
    private val origin = LatLon(40.0, -89.0)

    @Test fun distancesAreRight() {
        // One thousandth of a degree of latitude is about 111 meters.
        assertEquals(111.2, Geo.distance(origin, LatLon(40.001, -89.0)), 0.5)
        // Moving 50 m east and measuring back gives 50 m.
        assertEquals(50.0, Geo.distance(origin, Geo.move(origin, 90.0, 50.0)), 0.01)
    }

    @Test fun directionsAreRight() {
        assertEquals(0.0, Geo.bearing(origin, LatLon(40.01, -89.0)), 0.01)
        assertEquals(90.0, Geo.bearing(origin, Geo.move(origin, 90.0, 100.0)), 0.1)
        assertEquals("east", Geo.compassWord(90.0))
        assertEquals("north", Geo.compassWord(355.0))
        // Facing north, something due east is at 3 o'clock; due south at 6; due north at 12.
        assertEquals("3 o'clock", Geo.clockWord(0.0, 90.0))
        assertEquals("6 o'clock", Geo.clockWord(0.0, 180.0))
        assertEquals("12 o'clock", Geo.clockWord(0.0, 2.0))
        // Facing east, something due north is at 9 o'clock.
        assertEquals("9 o'clock", Geo.clockWord(90.0, 0.0))
        // Ahead, behind, left and right, relative to the way you face.
        assertEquals("ahead", Geo.relativeWord(0.0, 10.0))
        assertEquals("ahead", Geo.relativeWord(0.0, 345.0))
        assertEquals("ahead and slightly to the right", Geo.relativeWord(0.0, 40.0))
        assertEquals("ahead and slightly to the left", Geo.relativeWord(0.0, 330.0))
        assertEquals("to the right", Geo.relativeWord(0.0, 90.0))
        assertEquals("to the left", Geo.relativeWord(0.0, 270.0))
        assertEquals("behind you to the left", Geo.relativeWord(0.0, 220.0))
        assertEquals("behind you", Geo.relativeWord(0.0, 185.0))
        // Facing east, something due north is to the left.
        assertEquals("to the left", Geo.relativeWord(90.0, 0.0))
    }

    @Test fun distanceWords() {
        assertEquals("100 feet", Geo.distanceWords(30.48, Units.FEET))
        assertEquals("1.0 miles", Geo.distanceWords(1609.344, Units.FEET))
        assertEquals("30 meters", Geo.distanceWords(30.0, Units.METERS))
        assertEquals("1.5 kilometers", Geo.distanceWords(1500.0, Units.METERS))
        assertEquals("100 yards", Geo.distanceWords(91.44, Units.YARDS))
        assertEquals("1.0 miles", Geo.distanceWords(1609.344, Units.YARDS))
    }

    @Test fun routeShapeDecodes() {
        val pts = Router.decodePolyline6("__lhkA~bcwhDclAelA")
        assertEquals(2, pts.size)
        assertEquals(40.0, pts[0].lat, 1e-9); assertEquals(-89.0, pts[0].lon, 1e-9)
        assertEquals(40.001234, pts[1].lat, 1e-9); assertEquals(-88.998765, pts[1].lon, 1e-9)
    }

    @Test fun cellsCoverTheSearchCircle() {
        val p = LatLon(40.00499, -89.00499) // right at a cell corner
        val cells = RegionDb.cellsAround(p, 600.0)
        for (b in listOf(0.0, 90.0, 180.0, 270.0, 45.0, 225.0)) {
            val edge = Geo.move(p, b, 599.0)
            assertTrue("cell for bearing $b", RegionDb.cellOf(edge.lat, edge.lon) in cells)
        }
    }

    /** Main Street runs east-west; Oak Avenue north-south, crossing it. Oak is a divided road: two ways 16 m apart. */
    private fun town(): StreetArea {
        val n = HashMap<Long, LatLon>()
        fun at(id: Long, east: Double, north: Double) { n[id] = Geo.move(Geo.move(origin, 90.0, east), 0.0, north) }
        at(1, -200.0, 0.0); at(2, -8.0, 0.0); at(3, 8.0, 0.0); at(4, 200.0, 0.0)
        at(10, -8.0, -200.0); at(11, -8.0, 200.0)
        at(20, 8.0, -200.0); at(21, 8.0, 200.0)
        val ways = listOf(
            Way(1, "Main Street", "residential", listOf(1, 2, 3, 4)),
            Way(2, "Oak Avenue", "primary", listOf(10, 2, 11)),
            Way(3, "Oak Avenue", "primary", listOf(21, 3, 20)),
        )
        return StreetArea(origin, 600.0, ways, n)
    }

    @Test fun streetBeatsANearbyTrailWhenStandingStill() {
        // A street running east-west, and a footpath 30 m north of it, behind the houses.
        val n = HashMap<Long, LatLon>()
        n[1] = Geo.move(origin, 270.0, 100.0); n[2] = Geo.move(origin, 90.0, 100.0)
        val north = Geo.move(origin, 0.0, 30.0)
        n[3] = Geo.move(north, 270.0, 100.0); n[4] = Geo.move(north, 90.0, 100.0)
        val t = StreetArea(origin, 600.0, listOf(
            Way(1, "Emily Morgan Circle", "residential", listOf(1, 2)),
            Way(2, "Pecan Grove Levee Trail", "footway", listOf(3, 4)),
        ), n)
        // Standing in a house 17 m from the street and 13 m from the trail: it's the street.
        assertEquals("Emily Morgan Circle", t.nearestStreet(Geo.move(origin, 0.0, 17.0))!!.first.name)
        // Actually on the trail: it's the trail.
        assertEquals("Pecan Grove Levee Trail", t.nearestStreet(Geo.move(origin, 0.0, 28.0))!!.first.name)
    }

    @Test fun dividedRoadIsOneIntersection() {
        val t = town()
        assertEquals(2, t.intersections.size)
        assertEquals(1, t.clusters.size)
        assertEquals(t.clusterOf(2), t.clusterOf(3))
        assertEquals("Main Street and Oak Avenue", t.clusters[0].spoken())
        assertEquals("Main Street crossing Oak Avenue", t.clusters[0].crossing())
        assertEquals("Oak Avenue crossing Main Street", t.clusters[0].crossing("Oak Avenue"))
    }

    @Test fun cornerPicksStreetYouAreWalkingAlong() {
        val t = town()
        // 6 m north of Main, 4 m west of the near Oak carriageway: Oak is closer, but walking east means Main.
        val p = Geo.move(Geo.move(origin, 0.0, 6.0), 270.0, 12.0)
        assertEquals("Oak Avenue", t.nearestStreet(p)!!.first.name)
        assertEquals("Main Street", t.nearestStreet(p, 90.0)!!.first.name)
        assertEquals("Oak Avenue", t.nearestStreet(p, 0.0)!!.first.name)
    }

    @Test fun turnsAreAnnouncedBeforeAndAtTheTurn() {
        // Walk 150 m east, turn left, walk 100 m north.
        val corner = Geo.move(origin, 90.0, 150.0)
        val end = Geo.move(corner, 0.0, 100.0)
        val route = Route(
            listOf(origin, corner, end),
            listOf(
                Maneuver("Walk east on Main Street.", "Walk east on Main Street.", "Continue for 500 feet.", 0, 150.0, 2),
                Maneuver("Turn left onto Oak Avenue.", "Turn left onto Oak Avenue.", "Continue for 300 feet.", 1, 100.0, 15),
                Maneuver("You have arrived.", "You have arrived at your destination.", "", 2, 0.0, 4),
            ),
            250.0, 180.0,
        )
        val said = ArrayList<String>()
        var arrived = false
        var off = 0
        val f = RouteFollower(route, true, Units.FEET, { said.add(it) }, {}, { off++ }, { arrived = true })
        f.begin()
        var pos = origin
        fun step(bearing: Double, meters: Double) {
            var left = meters
            while (left > 0) {
                val d = minOf(2.0, left); pos = Geo.move(pos, bearing, d); left -= d
                f.onFix(Fix(pos, 5f, 1.4f, bearing, 0))
            }
        }
        step(90.0, 150.0)
        step(0.0, 100.0)
        val heads = said.filter { it.startsWith("In ") && it.contains("turn left onto Oak Avenue") }
        assertTrue("heads-up before the turn: $said", heads.isNotEmpty())
        assertTrue("turn said at the corner: $said", said.contains("Turn left onto Oak Avenue."))
        assertTrue("heads-up comes before the turn", said.indexOf(heads.first()) < said.indexOf("Turn left onto Oak Avenue."))
        assertEquals("never off route", 0, off)
        assertTrue("arrived", arrived)
    }

    @Test fun nextIntersectionAlongTheStreet() {
        val t = town()
        // 100 m west of the crossing, on Main Street, heading east: the crossing is about 92 m ahead.
        val p = Geo.move(origin, 270.0, 100.0)
        val n = t.nextIntersection(p, 90.0, "Main Street")!!
        assertEquals(92.0, n.distance, 2.0)
        assertEquals(4, n.legs)
        assertEquals("Main Street crossing Oak Avenue", n.intersection.crossing("Main Street"))
    }

    @Test fun dividedRoadCrossingHasFourLegs() {
        val t = town()
        assertEquals(4, t.legs(t.clusters[0]))
    }

    @Test fun oneWildFixDoesNotThrowYouAcrossTheStreet() {
        val f = PositionFilter()
        var pos = origin
        var t = 0L
        // Standing still with good 5 m fixes...
        repeat(10) { f.update(pos, 5f, 0f, t); t += 1000 }
        // ...then one bad 40 m jump with a 30 m accuracy.
        val (est, _) = f.update(Geo.move(pos, 90.0, 40.0), 30f, 0f, t)
        assertTrue("moved ${Geo.distance(origin, est)} m", Geo.distance(origin, est) < 8)
    }

    @Test fun smoothingKeepsUpWithWalking() {
        val f = PositionFilter()
        var pos = origin
        var t = 0L
        var est = origin
        repeat(60) {
            pos = Geo.move(pos, 90.0, 1.4); t += 1000
            est = f.update(pos, 5f, 1.4f, t).first
        }
        // After a minute of walking, the smoothed position is within about 3 m (10 feet) of the truth.
        assertTrue("lag ${Geo.distance(pos, est)} m", Geo.distance(pos, est) < 3)
    }

    @Test fun leavingTheRouteIsNoticed() {
        val end = Geo.move(origin, 90.0, 300.0)
        val route = Route(listOf(origin, end), listOf(
            Maneuver("Walk east.", "Walk east.", "", 0, 300.0, 2),
            Maneuver("You have arrived.", "You have arrived.", "", 1, 0.0, 4),
        ), 300.0, 200.0)
        var off = 0
        val f = RouteFollower(route, true, Units.FEET, {}, {}, { off++ }, {})
        var pos = origin
        repeat(40) { pos = Geo.move(pos, 0.0, 2.0); f.onFix(Fix(pos, 5f, 1.4f, 0.0, 0)) } // walk 80 m north instead
        assertTrue("off route noticed", off >= 1)
    }
}
