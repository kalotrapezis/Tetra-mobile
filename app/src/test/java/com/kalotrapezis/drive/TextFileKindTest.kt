package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFileKindTest {

    @Test fun `text, Greek and Windows line endings included, is text`() {
        assertEquals(TextFileRules.Kind.Text, TextFileRules.kind("Γεια σου κόσμε\r\n\tline two\n".toByteArray()))
        assertEquals(TextFileRules.Kind.Text, TextFileRules.kind(ByteArray(0)))
    }

    @Test fun `office files, PDF and anything with a NUL byte are not text (8 Nov - a ppt froze the editor)`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "[Content_Types].xml".toByteArray()
        assertEquals(TextFileRules.Kind.Binary, TextFileRules.kind(zip)) // .pptx .docx .xlsx .odt
        assertEquals(TextFileRules.Kind.Binary, TextFileRules.kind("%PDF-1.7\n".toByteArray()))
        assertEquals(TextFileRules.Kind.Binary, TextFileRules.kind("abc".toByteArray() + 0 + "def".toByteArray()))
        assertEquals(TextFileRules.Kind.Binary, TextFileRules.kind(ByteArray(100) { (it % 8 + 1).toByte() }))
    }

    @Test fun `text past half a megabyte is huge, even when only its start was read`() {
        assertEquals(TextFileRules.Kind.Huge, TextFileRules.kind("plain".toByteArray(), size = TextFileRules.HUGE + 1L))
        assertEquals(TextFileRules.Kind.Text, TextFileRules.kind("plain".toByteArray(), size = TextFileRules.HUGE.toLong()))
        // not text stays not text, whatever its size
        assertEquals(TextFileRules.Kind.Binary, TextFileRules.kind(byteArrayOf(0x50, 0x4B, 0x03, 0x04), size = 10_000_000))
    }
}
