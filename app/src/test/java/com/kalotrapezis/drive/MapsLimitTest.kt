package com.kalotrapezis.drive

import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ev.ArrayEdgeIntAccess
import com.graphhopper.routing.ev.EncodedValue
import com.graphhopper.routing.ev.MaxSpeed
import com.graphhopper.routing.util.parsers.OSMMaxSpeedParser
import com.graphhopper.storage.IntsRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The speed limit's sign: an OSM maxspeed through GraphHopper's own parser and graph storage, then what Directions shows. */
class MapsLimitTest {
    /** The sign shown for a way tagged [tags], as limitAt reads it from a graph. */
    private fun shown(vararg tags: Pair<String, String>): Int? {
        val ev = MaxSpeed.create().apply { init(EncodedValue.InitializerConfig()) }
        val access = ArrayEdgeIntAccess(1)
        val way = ReaderWay(1).apply { tags.forEach { (k, v) -> setTag(k, v) } }
        OSMMaxSpeedParser(ev).handleWayTags(0, access, way, IntsRef(1))
        return MapsRouting.posted(ev.getDecimal(false, 0, access), ev.getDecimal(true, 0, access))
    }

    @Test fun numbers() {
        assertEquals(50, shown("maxspeed" to "50"))
        assertEquals(130, shown("maxspeed" to "130"))
        assertEquals(25, shown("maxspeed" to "25")) // stored as 26 (2 km/h steps)
        assertEquals(80, shown("maxspeed" to "50 mph")) // 80.5 km/h
    }

    @Test fun noNumberNoSign() {
        assertNull(shown()) // untagged
        assertNull(shown("maxspeed" to "GR:urban")) // the law's limit, not a number on the way: not guessed
        assertNull(shown("maxspeed" to "none"))
        assertNull(shown("maxspeed" to "signals"))
    }

    @Test fun eachWayTheLower() {
        assertEquals(30, shown("maxspeed:forward" to "50", "maxspeed:backward" to "30"))
    }
}
