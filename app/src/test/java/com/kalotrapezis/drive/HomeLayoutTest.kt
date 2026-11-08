package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeLayoutTest {
    private val at = { items: List<HomeItem> -> items.filter { !it.hidden }.associate { it.id to (it.col to it.row) } }

    @Test fun nothingSavedIsSmallIconsOnly() {
        // the user, 25 Oct: small icons only by default; a tablet's 8 across take every part in two rows (Scanner & Codes two cells)
        val tablet = HomeLayout.place(HomeLayout.parse(null, HomeLayout.WIDE), 16)
        assertEquals(true, tablet.all { it.small })
        assertEquals(mapOf("sync" to (0 to 0), "settings" to (2 to 0), "scanner" to (10 to 0), "office" to (14 to 0), "text" to (0 to 2)), at(tablet).filterKeys { it in setOf("sync", "settings", "scanner", "office", "text") })
        assertEquals(6, HomeLayout.height(tablet, 16)) // Mail (31 Oct), Messages and Browser (2 Nov) on a third row
        // a phone: four across, Settings last
        val phone = HomeLayout.place(HomeLayout.parse(null, HomeLayout.NARROW), 8)
        assertEquals(0 to 0, at(phone)["sync"]); assertEquals(2 to 0, at(phone)["gallery"]); assertEquals(6 to 8, at(phone)["settings"]) // Mail (31 Oct), Messages and Browser (2 Nov), Pills (7 Nov) before it
    }

    @Test fun aSavedLayoutComesBackAsItWas() {
        val items = listOf(HomeItem("maps", size = HomeSize.Small, col = 3, row = 5), HomeItem("notes", hidden = true), HomeItem("settings", col = 0, row = 0))
        assertEquals("maps:s:3:5,notes:h:-1:-1,settings::0:0", HomeLayout.format(items))
        assertEquals(items, HomeLayout.parse(HomeLayout.format(items), listOf("maps", "notes", "settings")))
    }

    @Test fun aGoneAppletIsDroppedAndANewOneFindsAFreeCell() {
        val items = HomeLayout.place(HomeLayout.parse("old::0:0,maps:s:0:0,notes:s:4:0", listOf("maps", "notes", "contacts")), 8)
        assertEquals(listOf("maps", "notes", "contacts"), items.map { it.id })
        // the gap at (1, 0) closes (8 Nov: the grid flows); a new part comes small, after the others
        assertEquals(mapOf("maps" to (0 to 0), "notes" to (2 to 0), "contacts" to (4 to 0)), at(items))
    }

    @Test fun settingsIsNeverHidden() = assertFalse(HomeLayout.parse("settings:h:0:0", listOf("settings")).single().hidden)

    @Test fun droppedWhereAnotherIsTheOtherMovesOn() {
        val items = listOf(HomeItem("a", size = HomeSize.Small, col = 0, row = 0), HomeItem("b", size = HomeSize.Small, col = 2, row = 0), HomeItem("c", size = HomeSize.Small, col = 6, row = 4))
        // c onto b's cell: c takes it, b moves on to the next one
        assertEquals(mapOf("a" to (0 to 0), "c" to (2 to 0), "b" to (4 to 0)), at(HomeLayout.drop(items, "c", 2, 0, 8)))
        // dropped far below: it goes last, but no gap is left above it (8 Nov: the grid flows as a phone's)
        assertEquals(mapOf("b" to (0 to 0), "c" to (2 to 0), "a" to (4 to 0)), at(HomeLayout.drop(items, "a", 6, 18, 8)))
        // a group dropped near the edge is kept inside the grid, and pushes the small ones under it
        val big = items + HomeItem("g", col = 0, row = 8)
        assertEquals(4 to 0, at(HomeLayout.drop(big, "g", 12, 0, 16))["g"]) // pulled left: the cells before it were free
        val after = at(HomeLayout.drop(big, "g", 0, 0, 16))
        assertEquals(0 to 0, after["g"]); assertEquals(8 to 0, after["a"]); assertEquals(10 to 0, after["b"]); assertEquals(12 to 0, after["c"])
    }

    @Test fun madeLargeItPushesItsNeighbours() {
        val items = listOf(HomeItem("a", size = HomeSize.Small, col = 0, row = 0), HomeItem("b", size = HomeSize.Small, col = 2, row = 0))
        assertEquals(mapOf("a" to (0 to 0), "b" to (0 to 4)), at(HomeLayout.change(items, items[0].copy(size = HomeSize.Large), 8)))
        // made small again: b is pulled back up beside it
        val big = HomeLayout.change(items, items[0].copy(size = HomeSize.Large), 8)
        assertEquals(mapOf("a" to (0 to 0), "b" to (2 to 0)), at(HomeLayout.change(big, big[0].copy(size = HomeSize.Small), 8)))
        // hidden: b takes its cell; shown again: it comes after the others
        val hidden = HomeLayout.change(items, items[0].copy(hidden = true), 8)
        assertEquals(0 to 0, at(hidden)["b"])
        assertEquals(2 to 0, at(HomeLayout.change(hidden, hidden[0].copy(hidden = false, col = -1, row = -1), 8))["a"])
    }

    @Test fun aMediumPartIsItsIconInFourCells() {
        val items = listOf(HomeItem("a", size = HomeSize.Small, col = 0, row = 0), HomeItem("b", size = HomeSize.Small, col = 2, row = 2), HomeItem("c", size = HomeSize.Small, col = 4, row = 0))
        assertEquals(4 to 4, HomeLayout.size(HomeItem("maps", size = HomeSize.Medium), 16))
        assertEquals(4 to 4, HomeLayout.size(HomeItem("maps", size = HomeSize.Medium), 8)) // half a phone's width
        // a made medium: its 2 by 2 block; c beside it stays, b (inside it) flows on to the next free cell
        assertEquals(mapOf("a" to (0 to 0), "b" to (6 to 0), "c" to (4 to 0)), at(HomeLayout.change(items, items[0].copy(size = HomeSize.Medium), 8)))
        assertEquals("maps:m:2:4", HomeLayout.format(listOf(HomeItem("maps", size = HomeSize.Medium, col = 2, row = 4))))
        assertEquals(HomeSize.Medium, HomeLayout.parse("maps:m:2:4", listOf("maps")).single().size)
    }

    @Test fun anOldLayoutComesAcrossTwiceAsFine() {
        // 8 Nov: the grid twice as fine; a layout saved on the old grid keeps its places, every cell doubled
        assertEquals("maps:s:6:10,notes:h:-1:-1,settings::0:0", HomeLayout.doubled("maps:s:3:5,notes:h:-1:-1,settings::0:0"))
    }

    @Test fun aLargePartTakesWhatItsContentNeeds() {
        // 8 Nov: "no need for every widget to be the same size": lists taller, a couple of buttons a square
        assertEquals(6 to 6, HomeLayout.size(HomeItem("mail"), 16)); assertEquals(8 to 6, HomeLayout.size(HomeItem("mail"), 8))
        assertEquals(4 to 8, HomeLayout.size(HomeItem("notes"), 16)); assertEquals(4 to 8, HomeLayout.size(HomeItem("notes"), 8)) // tall, its pinned notes scroll
        assertEquals(8 to 4, HomeLayout.size(HomeItem("gallery"), 16)); assertEquals(8 to 2, HomeLayout.size(HomeItem("assistant"), 8))
        assertEquals(2 to 2, HomeLayout.size(HomeItem("mail", size = HomeSize.Small), 16))
    }

    @Test fun aLargePartIsAlwaysBiggerThanItsMedium() {
        // 8 Nov: Notes and Data large were the medium's 4 by 4, so making them large changed nothing
        for (cols in listOf(16, 8)) for (id in HomeLayout.WIDE) {
            val (lw, lh) = HomeLayout.size(HomeItem(id), cols); val (mw, mh) = HomeLayout.size(HomeItem(id, size = HomeSize.Medium), cols)
            if (lh >= mh) assertEquals("$id on $cols", true, lw * lh > mw * mh || id in setOf("sync", "settings", "assistant", "sms"))
        }
    }
}
