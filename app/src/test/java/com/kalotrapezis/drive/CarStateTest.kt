package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class CarStateTest {
    @Test fun keptAndReadBack() {
        val s = CarState(CarLayout.Three, listOf(CarApp.Maps, CarApp.Browser, CarApp.Phone), 2)
        assertEquals(s, CarState.parse(s.toString()))
        assertEquals(CarState(), CarState.parse(null))
        assertEquals(CarState(), CarState.parse("Nonsense|Maps|0"))
        // a page twice or too few for the layout: mended, not dropped
        assertEquals(CarState(CarLayout.Half, listOf(CarApp.Maps, CarApp.Browser), 1), CarState.parse("Half|Maps,Maps|5"))
    }

    @Test fun layoutsKeepThePagesInOrder() {
        val s = CarState(CarLayout.Wide, listOf(CarApp.Phone, CarApp.Maps), 1)
        assertEquals(listOf(CarApp.Phone, CarApp.Maps, CarApp.Browser), s.laid(CarLayout.Three).panes)
        assertEquals(CarState(CarLayout.Full, listOf(CarApp.Phone), 0), s.laid(CarLayout.Full))
    }

    @Test fun theRailPutsAPageInTheBigPane() {
        val s = CarState(CarLayout.Wide, listOf(CarApp.Maps, CarApp.Browser), 1)
        assertEquals(CarState(CarLayout.Wide, listOf(CarApp.Phone, CarApp.Maps), 0), s.put(CarApp.Phone)) // the map slides to the small pane
        // the map in the small pane: one tap brings it back to the big one
        assertEquals(CarState(CarLayout.Wide, listOf(CarApp.Maps, CarApp.Phone), 0), s.put(CarApp.Phone).put(CarApp.Maps))
        assertEquals(s.copy(focus = 0), s.put(CarApp.Maps)) // in the big pane already: stays
        assertEquals(listOf(CarApp.Messages, CarApp.Maps, CarApp.Browser), CarState(CarLayout.Three, listOf(CarApp.Maps, CarApp.Browser, CarApp.Messages), 2).put(CarApp.Messages).panes)
        assertEquals(listOf(CarApp.Phone), CarState(CarLayout.Full, listOf(CarApp.Maps), 0).put(CarApp.Phone).panes)
    }

    @Test fun exposeSwapsOrReplaces() {
        val s = CarState(CarLayout.Wide, listOf(CarApp.Browser, CarApp.Maps), 0)
        assertEquals(CarState(CarLayout.Wide, listOf(CarApp.Maps, CarApp.Browser), 0), s.placed(0, CarApp.Maps)) // shown in the other pane: swapped
        assertEquals(CarState(CarLayout.Wide, listOf(CarApp.Browser, CarApp.Phone), 1), s.placed(1, CarApp.Phone)) // not shown: replaces
        assertEquals(s.copy(focus = 1), s.placed(1, CarApp.Maps)) // already there: kept
        assertEquals(s, s.placed(5, CarApp.Phone))
    }

    @Test fun spansFillTheWidth() {
        assertEquals(listOf(0 to 595, 603 to 397), carSpans(1000, 8, listOf(0.6f, 0.4f)))
        val three = carSpans(1024, 8, listOf(0.5f, 0.25f, 0.25f))
        assertEquals(1024, three.last().let { it.first + it.second })
    }

    @Test fun live24TakesAHeartedStation() {
        val marks = listOf(Bookmark("https://example.com", "x"), Bookmark("https://www.live24.gr/radio/kosmos.jsp", "Kosmos"))
        assertEquals("https://www.live24.gr/radio/kosmos.jsp", carLive24(marks, null))
        assertEquals("https://live24.gr/radio/x", carLive24(emptyList(), "https://live24.gr/radio/x"))
        assertEquals("https://www.live24.gr", carLive24(emptyList(), "https://notlive24.gr/a"))
    }

    @Test fun anAppOpensBesideTheMap() {
        // one pane: two, the map and the app
        assertEquals(CarState(CarLayout.Wide, listOf(CarApp.Maps, CarApp.Messages), 1), CarState(CarLayout.Full, listOf(CarApp.Maps), 0).beside(CarApp.Messages))
        // the map not shown: it comes back in the first pane
        assertEquals(CarState(CarLayout.Half, listOf(CarApp.Maps, CarApp.Messages), 1), CarState(CarLayout.Half, listOf(CarApp.Phone, CarApp.Browser), 1).beside(CarApp.Messages))
        // shown already: only focused; the map's own pane never taken
        assertEquals(1, CarState(CarLayout.Wide, listOf(CarApp.Maps, CarApp.Phone), 0).beside(CarApp.Phone).focus)
        assertEquals(listOf(CarApp.Maps, CarApp.Browser), CarState(CarLayout.Wide, listOf(CarApp.Maps, CarApp.Phone), 0).beside(CarApp.Browser).panes)
    }

    @Test fun pagesByTheirNames() {
        assertEquals(CarApp.Messages, carAppFor("τα Μηνύματα"))
        assertEquals(CarApp.Maps, carAppFor("τον χάρτη"))
        assertEquals(CarApp.Phone, carAppFor("Τηλέφωνο"))
        assertEquals(CarApp.Browser, carAppFor("Spotify"))
        assertEquals(null, carAppFor("Viber"))
    }
}
