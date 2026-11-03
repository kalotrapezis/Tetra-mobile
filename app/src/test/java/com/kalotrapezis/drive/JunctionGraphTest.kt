package com.kalotrapezis.drive

import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadClassLink
import com.graphhopper.routing.ev.Roundabout
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.search.KVStorage
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.PointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A turn's crossing read from small graphs drawn in metres: the roads a driver sees there, no more (the user, 3 Nov). */
class JunctionGraphTest {
    private val em = EncodingManager.start().add(VehicleAccess.create("car")).add(Roundabout.create())
        .add(RoadClass.create()).add(RoadClassLink.create()).build()
    private val g = BaseGraph.Builder(em).build().apply { create(1000) }
    private val access = em.getBooleanEncodedValue("car_access"); private val ring = em.getBooleanEncodedValue("roundabout")
    private val kind = em.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java); private val ramp = em.getBooleanEncodedValue(RoadClassLink.KEY)
    private val at = HashMap<Int, Pair<Double, Double>>()

    private fun lat(y: Double) = 40.95 + y / 111_320.0
    private fun lng(x: Double) = 24.14 + x / (111_320.0 * Math.cos(Math.toRadians(40.95)))
    /** Node [id] at [x] m east and [y] m north. */
    private fun node(id: Int, x: Double, y: Double) { g.nodeAccess.setNode(id, lat(y), lng(x)); at[id] = x to y }
    /** A road from [a] to [b]: [oneWay] only that way; its [name], class and whether it is a ramp. */
    private fun road(a: Int, b: Int, name: String = "", cls: RoadClass = RoadClass.RESIDENTIAL, oneWay: Boolean = false,
                     link: Boolean = false, round: Boolean = false) {
        val (x1, y1) = at.getValue(a); val (x2, y2) = at.getValue(b)
        g.edge(a, b).setDistance(metresBetween(lat(y1), lng(x1), lat(y2), lng(x2))).set(access, true, !oneWay).set(kind, cls)
            .set(ramp, link).set(ring, round).setKeyValues(if (name.isEmpty()) emptyMap() else mapOf("street_name" to KVStorage.KValue(name)))
    }
    private fun route(vararg ids: Int) = PointList().apply { ids.forEach { val (x, y) = at.getValue(it); add(lat(y), lng(x)) } }
    private var far = 100
    /** A road's far end, [x],[y] m away, as a new node: its id. */
    private fun end(x: Double, y: Double) = far++.also { node(it, x, y) }

    @Test fun sharpLeftBesideAnotherCrossing() {
        // three roads: in from the south, a sharp left back south-west, on north 20 m to a four-road crossing; a driveway east
        node(1, 0.0, 0.0); node(2, 0.0, 20.0)
        val s = end(0.0, -100.0); val sw = end(-90.0, -50.0); val dw = end(40.0, 0.0)
        road(s, 1, "Α"); road(1, sw, "Β"); road(1, 2, "Α"); road(1, dw, cls = RoadClass.SERVICE)
        road(2, end(-100.0, 20.0), "Γ"); road(2, end(100.0, 20.0), "Γ"); road(2, end(0.0, 120.0), "Α")
        val j = MapsRouting.crossing(g, em, 1, route(s, 1, sw), 1, 2, false)!!
        println("sharp left: $j")
        assertEquals(3, j.arms.size)
        assertTrue(j.arms[j.taken] < -110)
        assertTrue(j.then.isEmpty()) // the route does not turn at the other crossing
    }

    @Test fun fiveStreetsDrawnAsThreeNodes() {
        // Νικήσιανη's kind: six streets meeting at three nodes 9 m apart, none of which has five
        node(1, 0.0, 0.0); node(2, 8.0, 4.0); node(3, 3.0, 9.0)
        road(1, 2); road(2, 3); road(1, 3)
        val s = end(0.0, -100.0); val nw = end(-70.0, 80.0)
        road(s, 1); road(1, end(-100.0, -10.0)); road(2, end(100.0, 0.0)); road(2, end(70.0, -70.0)); road(3, end(0.0, 100.0)); road(3, nw)
        val j = MapsRouting.crossing(g, em, 1, route(s, 1, 3, nw), 1, 3, false)!!
        println("six streets: $j")
        assertEquals(6, j.arms.size)
        assertTrue(j.arms[j.taken] in -60.0..-20.0)
    }

    @Test fun dualCarriagewayIsOneRoad() {
        // Δράμα, Καραϊσκάκη at Εθνικής Αμύνης: its two halves 9 m apart, the crossing street drawn twice between them
        node(1, 0.0, 0.0); node(2, 9.0, 0.0)
        val w = end(-100.0, 10.0); val n = end(9.0, 100.0)
        road(w, 1, "Καραϊσκάκη", RoadClass.SECONDARY); road(1, 2, "Καραϊσκάκη", RoadClass.SECONDARY); road(1, 2, "Καραϊσκάκη", RoadClass.SECONDARY)
        road(end(0.0, 100.0), 1, "Εθνικής Αμύνης", RoadClass.SECONDARY, oneWay = true); road(1, end(0.0, -100.0), "Εθνικής Αμύνης", RoadClass.SECONDARY, oneWay = true)
        road(end(9.0, -100.0), 2, "Εθνικής Αμύνης", RoadClass.SECONDARY, oneWay = true); road(2, n, "Εθνικής Αμύνης", RoadClass.RESIDENTIAL, oneWay = true)
        road(2, end(100.0, 0.0), "Δραγούμη", RoadClass.SECONDARY, oneWay = true)
        val j = MapsRouting.crossing(g, em, 2, route(w, 1, 2, n), 2, 3, false)!!
        println("dual carriageway: $j")
        assertEquals(4, j.arms.size)
        assertTrue(j.arms[j.taken] < -45)
    }

    @Test fun staggeredCrossingIsTwo() {
        // in from the west, left onto the main road, right again 20 m on
        node(1, 0.0, 0.0); node(2, 0.0, 20.0)
        val w = end(-100.0, 0.0); val e = end(100.0, 20.0)
        road(w, 1); road(1, end(0.0, -100.0), "Κύριος", RoadClass.PRIMARY); road(1, 2, "Κύριος", RoadClass.PRIMARY)
        road(2, end(0.0, 120.0), "Κύριος", RoadClass.PRIMARY); road(2, e)
        val j = MapsRouting.crossing(g, em, 1, route(w, 1, 2, e), 1, 2, false)!!
        println("staggered: $j")
        assertEquals(3, j.arms.size); assertEquals(-90.0, j.arms[j.taken], 5.0)
        assertEquals(2, j.then.size); assertEquals(0.0, j.then[j.thenTaken], 5.0) // east: straight on from the way in
        assertEquals(listOf(0, 2, 2, 2, 0), j.roads)
        assertTrue(j.busy)
    }

    @Test fun uTurnAndRamp() {
        node(1, 0.0, 0.0)
        val s = end(0.0, -100.0)
        road(s, 1); road(1, end(-100.0, 0.0)); road(1, end(100.0, 0.0))
        val u = MapsRouting.crossing(g, em, 1, route(s, 1, s), 1, 2, false, uTurn = -1)!!
        println("u-turn: $u")
        assertTrue(u.uTurn && u.busy && u.arms[u.taken] < 0)
        // a motorway exit: the motorway on straight, the ramp off it 20° right
        node(2, 0.0, 1000.0)
        val m = end(0.0, 900.0); val r = end(35.0, 1095.0)
        road(m, 2, cls = RoadClass.MOTORWAY, oneWay = true); road(2, end(0.0, 1100.0), cls = RoadClass.MOTORWAY, oneWay = true)
        road(2, r, cls = RoadClass.MOTORWAY, oneWay = true, link = true)
        val x = MapsRouting.crossing(g, em, 2, route(m, 2, r), 1, 2, false)!!
        println("exit: $x")
        assertEquals(listOf(2, 2, -1), x.roads); assertTrue(x.busy && x.arms[x.taken] > 10)
    }

    @Test fun roundaboutExit() {
        node(1, 0.0, -15.0); node(2, 15.0, 0.0); node(3, 0.0, 15.0); node(4, -15.0, 0.0)
        for ((a, b) in listOf(1 to 2, 2 to 3, 3 to 4, 4 to 1)) road(a, b, oneWay = true, round = true)
        val s = end(0.0, -100.0); val n = end(0.0, 100.0)
        road(s, 1); road(2, end(100.0, 0.0)); road(3, n); road(4, end(-100.0, 0.0))
        val j = MapsRouting.crossing(g, em, 1, route(s, 1, 2, 3, n), 1, 3, true, exit = 2)!!
        println("roundabout: $j")
        assertEquals(4, j.arms.size); assertEquals(2, j.exit); assertEquals(0.0, j.arms[j.taken], 5.0)
    }
}
