package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticesTest {
    private fun n(channel: String, ongoing: Boolean = false) = Notices.Notice(Notices.applet(channel), "t", "", 0, ongoing, null, null, 0, channel)

    /** Home's numbers count news only (the user, 4 Nov: "the phone, I don't have a call and I have a 1"). */
    @Test fun onlyNewsIsCounted() {
        val counts = Notices.counts(listOf(n("where_nobadge"), n("calls_from_computer_nobadge"), n("phone_blocked_nobadge"), n("listening_nobadge", ongoing = true),
            n("phone_missed_nobadge"), n("mail_nobadge"), n("mail_nobadge"), n("files_from_computer_nobadge"), n("sync_nobadge", ongoing = true)))
        assertEquals(mapOf("phone" to 1, "mail" to 2, "sync" to 1), counts)
    }

    @Test fun anAppletWindowsHomeId() {
        assertEquals("sms", Notices.homeOf(Applet.Messages))
        assertEquals("gallery", Notices.homeOf(Applet.Photos))
        assertEquals("mail", Notices.homeOf(Applet.Mail))
    }
}
