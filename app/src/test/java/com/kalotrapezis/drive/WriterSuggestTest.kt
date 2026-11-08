package com.kalotrapezis.drive

import com.kalotrapezis.drive.WriterSuggest.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review › ✦ Suggestions (WriterSuggest): the model's JSON read, what changes nothing dropped, accepted ones put in at the right place. */
class WriterSuggestTest {
    private val text = "Η εργασια αυτή εξετάζει το θέμα. Τα αποτελέσματα δειχνουν μια σαφή τάση."

    @Test fun readsTheReplyAndPlacesEachFind() {
        val reply = """{"suggestions":[
            {"find":"εργασια","replace":"εργασία","reason":"τόνος"},
            {"find":"δειχνουν","replace":"δείχνουν","reason":"τόνος"}]}"""
        val s = WriterSuggest.parse(reply, text, base = 100)
        assertEquals(listOf(Suggestion(102, "εργασια", "εργασία", "τόνος"), Suggestion(100 + text.indexOf("δειχνουν"), "δειχνουν", "δείχνουν", "τόνος")), s)
    }

    @Test fun malformedRepliesGiveNone() {
        for (r in listOf("", "Sorry, I cannot help.", "{\"suggestions\":[{\"find\":", "{\"other\":1}", "[1, 2, \"x\"]", "{\"suggestions\":\"none\"}"))
            assertEquals(r, emptyList<Suggestion>(), WriterSuggest.parse(r, text))
        // a fence and words around the JSON, or a bare array, still read
        assertEquals(1, WriterSuggest.parse("Here:\n```json\n{\"suggestions\":[{\"find\":\"θέμα\",\"replace\":\"ζήτημα\",\"reason\":\"ύφος\"}]}\n```", text).size)
        assertEquals(1, WriterSuggest.parse("[{\"find\":\"θέμα\",\"replace\":\"ζήτημα\",\"reason\":\"ύφος\"}]", text).size)
    }

    @Test fun dropsUnfoundNoOpAndOverlapping() {
        val reply = """{"suggestions":[
            {"find":"δεν υπάρχει","replace":"κάτι","reason":"x"},
            {"find":"θέμα","replace":"θέμα","reason":"ίδιο"},
            {"find":"Εργασια","replace":"Εργασία","reason":"όχι ακριβώς"},
            {"find":"μια","reason":"χωρίς replace"},
            {"find":"σαφή τάση","replace":"ξεκάθαρη τάση","reason":"ύφος"},
            {"find":"τάση","replace":"κατεύθυνση","reason":"πάνω στην προηγούμενη"},
            {"find":"μια σαφή","replace":"","reason":"διαγραφή"}]}"""
        val s = WriterSuggest.parse(reply, text)
        assertEquals(listOf("σαφή τάση"), s.map { it.find }) // "μια σαφή" overlaps it too
    }

    @Test fun acceptingSeveralInTurnKeepsTheOffsets() {
        val reply = """{"suggestions":[
            {"find":"Η εργασια","replace":"Η παρούσα εργασία","reason":"a"},
            {"find":"θέμα","replace":"ζήτημα","reason":"b"},
            {"find":"δειχνουν","replace":"δείχνουν","reason":"c"},
            {"find":"μια ","replace":"","reason":"d"}]}"""
        var doc = text
        var waiting = WriterSuggest.parse(reply, doc)
        assertEquals(4, waiting.size)
        // accepted out of order, as the user may: the second, the first, the last; the third rejected
        for (pick in listOf("θέμα", "Η εργασια", "μια ")) {
            val s = WriterSuggest.locate(doc, waiting).first { it.find == pick }
            assertTrue(doc.regionMatches(s.at, s.find, 0, s.find.length))
            doc = doc.substring(0, s.at) + s.replace + doc.substring(s.end)
            waiting = WriterSuggest.after(waiting, s)
        }
        assertEquals("Η παρούσα εργασία αυτή εξετάζει το ζήτημα. Τα αποτελέσματα δειχνουν σαφή τάση.", doc)
        assertEquals(listOf(doc.indexOf("δειχνουν")), waiting.map { it.at })
    }

    @Test fun locateFollowsTypingAndDropsWhatIsGone() {
        val s = WriterSuggest.parse("""[{"find":"θέμα","replace":"ζήτημα","reason":""},{"find":"σαφή","replace":"καθαρή","reason":""}]""", text)
        val typed = "Νέα αρχή. " + text.replace("σαφή", "ήπια")
        assertEquals(listOf(typed.indexOf("θέμα")), WriterSuggest.locate(typed, s).map { it.at })
    }

    @Test fun chunksCutAtParagraphsAndCoverTheText() {
        val para = "Μια παράγραφος με λέξεις. ".repeat(40) + "\n"
        val doc = para.repeat(10)
        val c = WriterSuggest.chunks(doc, 3000)
        assertTrue(c.all { it.second.length <= 3000 && it.second.endsWith("\n") })
        assertEquals(doc, c.joinToString("") { it.second })
        c.forEach { (start, piece) -> assertEquals(piece, doc.substring(start, start + piece.length)) }
        // one paragraph longer than a piece: cut at a space
        val long = "λέξη ".repeat(2000)
        assertEquals(long, WriterSuggest.chunks(long, 1000).joinToString("") { it.second })
    }
}
