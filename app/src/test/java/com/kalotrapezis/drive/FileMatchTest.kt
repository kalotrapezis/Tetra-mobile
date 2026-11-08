package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class FileMatchTest {
    private fun hits(said: String, name: String) = FileMatch.query(said).let { FileMatch.score(it, name) == it.size }

    @Test fun `a spoken name finds the file without its dots, dashes and underscores`() {
        assertEquals(true, hits("Αποσπάσματα md", "ΑΠΟΣΠΑΣΜΑΤΑ.md"))
        assertEquals(true, hits("εργασία τελική docx", "Εργασία_τελική-v2.docx"))
        assertEquals(true, hits("τελική εργασία", "Εργασία_τελική-v2.docx")) // another order
        assertEquals(true, hits("βαθμολογίες", "Βαθμολογίες.md"))
    }

    @Test fun `another ending still finds it, a missing word does not`() {
        assertEquals(true, hits("βαθμολογίες", "Βαθμολογία μου.pdf"))
        assertEquals(true, hits("σημειώσεις μαθήματος", "Σημειώσεις μαθημάτων.pdf"))
        assertEquals(false, hits("σημειώσεις φυσικής", "Σημειώσεις μαθημάτων.pdf"))
    }

    @Test fun `final sigma and accents do not matter`() {
        assertEquals(true, hits("οδηγιες αξιολογησης", "Οδηγίες αξιολόγησης γραπτών εργασιών NUP.pdf"))
        assertEquals(FileMatch.words("Σπουδές"), FileMatch.words("σπουδεσ"))
    }
}
