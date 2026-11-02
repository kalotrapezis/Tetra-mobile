package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTest {
    private fun text(id: Long, thread: Long, address: String, at: Long, type: Int = 1, read: Boolean = true) = SmsText(id, thread, address, "m$id", at, type, read)

    @Test fun oneRowAThreadNewestFirstWithItsUnread() {
        val t = SmsRules.threads(listOf(text(1, 7, "+306912345678", 100, read = false), text(2, 7, "+306912345678", 300, type = 2, read = true),
            text(3, 9, "6900000000", 200, read = false), text(4, 7, "+306912345678", 50, read = false)))
        assertEquals(listOf(7L, 9L), t.map { it.id })
        assertEquals(2L, t[0].last.id) // the user's own reply is the last
        assertEquals(2, t[0].unread) // only theirs count
        assertEquals(1, t[1].unread)
    }

    @Test fun aNumberFindsItsConversationHoweverWritten() {
        val t = SmsRules.threads(listOf(text(1, 7, "+30 691 234 5678", 100)))
        assertEquals(7L, SmsRules.threadOf(t, "6912345678")?.id)
        assertEquals(null, SmsRules.threadOf(t, "6998765432"))
        assertTrue(SmsRules.isNumber("+30 691 234-5678")); assertFalse(SmsRules.isNumber("Μαρία")); assertFalse(SmsRules.isNumber("12"))
    }

    @Test fun archivedUntilANewerMessageAndKeptAsText() {
        val map = SmsRules.parseArchive(SmsRules.formatArchive(mapOf(7L to 300L, 9L to 10L)))
        assertEquals(mapOf(7L to 300L, 9L to 10L), map)
        val t = SmsRules.threads(listOf(text(1, 7, "1234567", 300), text(2, 9, "7654321", 200)))
        assertTrue(SmsRules.archived(map, t[0]))
        assertFalse(SmsRules.archived(map, t[1])) // a message came after it was archived: back in the list
        assertEquals(emptyMap<Long, Long>(), SmsRules.parseArchive("junk,:,"))
    }
}
