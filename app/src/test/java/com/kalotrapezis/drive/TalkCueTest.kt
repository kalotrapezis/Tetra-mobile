package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class TalkCueTest {
    @Test fun `the answer's weather picks the cue, rain before clouds`() {
        assertEquals(TalkCue.Rain, TalkCue.of("Αύριο θα έχει συννεφιά και βροχή το απόγευμα."))
        assertEquals(TalkCue.Snow, TalkCue.of("Χιονίζει στα ορεινά."))
        assertEquals(TalkCue.Cloud, TalkCue.of("Mostly cloudy today."))
        assertEquals(TalkCue.Sun, TalkCue.of("Λιακάδα όλη μέρα, 22 βαθμοί."))
        assertEquals(TalkCue.None, TalkCue.of("Η DuckDuckGo είναι ανεξάρτητη εταιρεία."))
    }

    @Test fun `a search tool shows the magnifier, others nothing`() {
        assertEquals(TalkCue.Search, TalkCue.ofTool("web_search"))
        assertEquals(TalkCue.Note, TalkCue.ofTool("note_pin"))
        assertEquals(TalkCue.Call, TalkCue.ofTool("contact_call"))
        assertEquals(TalkCue.Contacts, TalkCue.ofTool("contacts_find"))
        assertEquals(TalkCue.Pill, TalkCue.ofTool("pills_taken"))
        assertEquals(TalkCue.None, TalkCue.ofTool("no_such_tool"))
    }

    @Test fun `cold trembles, hot wears the hat, by words or by degrees`() {
        assertEquals(TalkMood.Cold, TalkMood.of("Κάνει πολύ κρύο σήμερα."))
        assertEquals(TalkMood.Hot, TalkMood.of("Καύσωνας στην Αθήνα."))
        assertEquals(TalkMood.Hot, TalkMood.of("Θα φτάσει τους 35 βαθμούς."))
        assertEquals(TalkMood.Cold, TalkMood.of("Low of 3° tonight."))
        assertEquals(TalkMood.Normal, TalkMood.of("18 βαθμοί και λιακάδα."))
    }

    @Test fun `each voice keeps one colour, the phone's voice the mascot's yellow`() {
        assertEquals(CHARACTER_COLORS[0], characterColor(null))
        assertEquals(CHARACTER_COLORS[0], characterColor(""))
        assertEquals(characterColor("voice-a"), characterColor("voice-a"))
        assert(characterColor("voice-a") in CHARACTER_COLORS)
    }
}
