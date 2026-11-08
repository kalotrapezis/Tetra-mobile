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
        assertEquals(TalkCue.None, TalkCue.ofTool("note_pin"))
    }

    @Test fun `cold trembles, hot wears the hat, by words or by degrees`() {
        assertEquals(TalkMood.Cold, TalkMood.of("Κάνει πολύ κρύο σήμερα."))
        assertEquals(TalkMood.Hot, TalkMood.of("Καύσωνας στην Αθήνα."))
        assertEquals(TalkMood.Hot, TalkMood.of("Θα φτάσει τους 35 βαθμούς."))
        assertEquals(TalkMood.Cold, TalkMood.of("Low of 3° tonight."))
        assertEquals(TalkMood.Normal, TalkMood.of("18 βαθμοί και λιακάδα."))
    }

    @Test fun `each voice keeps one colour, the phone's voice the orange`() {
        assertEquals(ASSISTANT_COLOR, characterColor(null))
        assertEquals(ASSISTANT_COLOR, characterColor(""))
        assertEquals(characterColor("voice-a"), characterColor("voice-a"))
        assert(characterColor("voice-a") in CHARACTER_COLORS)
    }
}
