package com.kalotrapezis.drive

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Dictated words go in where the cursor is, spaced from the text either side, and the cursor ends after them. */
class DictateTest {

    @Test
    fun `into an empty field`() = assertEquals(TextFieldValue("hello", TextRange(5)), TextFieldValue("").dictated(" hello "))

    @Test
    fun `at the cursor, spaced both sides`() =
        assertEquals(TextFieldValue("meet at noon today", TextRange(13)), TextFieldValue("meet attoday", TextRange(7)).dictated("noon"))

    @Test
    fun `a selection is replaced`() =
        assertEquals(TextFieldValue("weather in Athens", TextRange(17)), TextFieldValue("example.com", TextRange(0, 11)).dictated("weather in Athens"))

    @Test
    fun `a string gets the words at its end`() {
        assertEquals("On my way", "On my".dictated("way"))
        assertEquals("On my way", "On my ".dictated("way"))
    }
}
