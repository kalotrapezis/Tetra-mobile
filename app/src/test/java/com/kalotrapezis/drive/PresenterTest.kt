package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenterTest {
    @Test fun `without the model it reads the page's first sentences, ending at a full stop`() {
        val page = "Καλημέρα. Σήμερα έχεις δύο συναντήσεις και ένα μάθημα στις έξι. Ο καιρός θα είναι καλός, 18 βαθμοί. " + "Λεπτομέρειες ".repeat(80)
        val said = Presenter.opening(page)
        assertTrue(said.length <= 500)
        assertTrue(said.endsWith("."))
        assertEquals("Λίγα λόγια", Presenter.opening("Λίγα λόγια"))
    }
}
