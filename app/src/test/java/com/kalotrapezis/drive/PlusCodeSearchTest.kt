package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlusCodeSearchTest {
    private val all = listOf(Poi("village", 38.2103, 23.5470, "Σκούρτα", "Skourta"), Poi("village", 41.13, 24.88, "Ξάνθη", "Xanthi"))

    @Test fun googlesCopyWithPostcode() {
        for (q in listOf("6G7X+5G, Σκούρτα 190 12", "6G7X+5G Σκούρτα", "6G7X+5G, Σκούρτα 190 12, Ελλάδα", "6G7X+5G")) {
            val p = MapsSearch.spot(all, q, 38.27, 23.58)
            assertNotNull(q, p)
            assertEquals(q, 38.21, p!!.lat, 0.01); assertEquals(q, 23.55, p.lng, 0.01)
        }
    }
}
