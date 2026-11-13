package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class CarDockTest {
    @Test fun aWideScreenHasTheDockDownTheDriversSide() {
        assertEquals(DockSide.Left, dockFor(DockSide.Auto, 1280, 720, driverRight = false)) // the user's unit, left-hand drive
        assertEquals(DockSide.Right, dockFor(DockSide.Auto, 1024, 600, driverRight = true))
    }
    @Test fun aNarrowerScreenHasItAlongTheBottom() {
        assertEquals(DockSide.Bottom, dockFor(DockSide.Auto, 800, 600, driverRight = false))
        assertEquals(DockSide.Bottom, dockFor(DockSide.Auto, 400, 800, driverRight = true)) // a phone upright
    }
    @Test fun aChosenSideStays() = assertEquals(DockSide.Bottom, dockFor(DockSide.Bottom, 1280, 720, driverRight = false))
}

class CarDockAppsTest {
    @Test fun anAppNotOnTheDockJoinsItAndTheOldestLeaves() =
        assertEquals(listOf("media", "phone", "messages", "app:com.spotify.music/.Main"), docked(listOf("maps", "media", "phone", "messages"), "app:com.spotify.music/.Main"))
    @Test fun anAppOnTheDockStaysWhereItIs() = assertEquals(listOf("maps", "media", "phone", "messages"), docked(listOf("maps", "media", "phone", "messages"), "phone"))
}
