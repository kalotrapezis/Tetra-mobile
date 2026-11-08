package com.kalotrapezis.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Passwords applet (Passwords.kt): Google's CSV, the generator, notes kept, 2FA between devices. */
class PasswordsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun googleCsv() {
        // Google Password Manager › Settings › Export passwords: name,url,username,password,note (invented rows)
        val csv = "name,url,username,password,note\n" +
            "shop.example.gr,https://shop.example.gr/login,maria@example.org,\"pa,ss\"\"1\",\"two\nlines\"\n" +
            "com.example.app,android://AbCd1234==@com.example.app/,maria,app-pw,\n" +
            "Old Forum,,nick,forum-pw,\n" +
            "empty.example,https://empty.example/,x,,\n"
        assertEquals(listOf(
            SavedLogin("shop.example.gr", "maria@example.org", "pa,ss\"1", "two\nlines"),
            SavedLogin("com.example.app", "maria", "app-pw"),
            SavedLogin("old forum", "nick", "forum-pw")), FirefoxImport.read(csv)!!.logins)
    }

    @Test fun noteSurvivesStoreSaveAndSync() {
        val v = BrowserVault(listOf(SavedLogin("a.gr", "teo", "pw", "PIN 1234")))
        assertEquals(v, BrowserVault.of(v.json()))
        assertEquals(v, SecretSet().record(v, 1).vault())
        // a page's form saving the same login asks nothing; a new password from it keeps the note
        assertTrue(v.news(BrowserVault(listOf(SavedLogin("a.gr", "teo", "pw")))).empty)
        assertEquals("PIN 1234", v.with(BrowserVault(listOf(SavedLogin("a.gr", "teo", "new")))).logins.single().note)
        // ♥ kept through the store and sync, with a note or without; a page's save keeps it
        listOf(SavedLogin("p.gr", "u", "pw", pinned = true), SavedLogin("p.gr", "v", "pw", "n", pinned = true)).forEach { l ->
            assertEquals(listOf(l), BrowserVault.of(BrowserVault(listOf(l)).json()).logins)
            assertEquals(listOf(l), SecretSet().record(BrowserVault(listOf(l)), 1).vault().logins)
        }
        assertEquals(true, BrowserVault(listOf(SavedLogin("p.gr", "u", "pw", pinned = true))).with(BrowserVault(listOf(SavedLogin("p.gr", "u", "new")))).logins.single().pinned)
        // a login without a note is written as before notes, so an older sync file still reads the same
        assertEquals("[\"a.gr\",\"teo\",\"pw\"]", SecretSet.keyed(BrowserVault(listOf(SavedLogin("a.gr", "teo", "pw")))).values.single())
    }

    @Test fun generator() {
        val r = java.util.Random(7)
        repeat(200) {
            val p = PasswordGen.password(PasswordGen.Options(length = 12), r)
            assertEquals(12, p.length)
            assertTrue(p.any(Char::isUpperCase) && p.any(Char::isLowerCase) && p.any(Char::isDigit) && p.any { it in PasswordGen.SYMBOLS })
        }
        val digits = PasswordGen.password(PasswordGen.Options(length = 6, upper = false, lower = false, symbols = false), r)
        assertTrue(digits.all(Char::isDigit))
        assertFalse(PasswordGen.password(PasswordGen.Options(length = 64, noSimilar = true), r).any { it in "Il1O0o|" })
        assertEquals(6 * Math.log(10.0) / Math.log(2.0), PasswordGen.Options(6, upper = false, lower = false, symbols = false).bits, 1e-9)
        // nothing chosen: lower case, never an empty password
        assertEquals(8, PasswordGen.password(PasswordGen.Options(8, false, false, false, false), r).length)
        val words = listOf("alpha", "bravo", "charlie", "delta")
        val pp = PasswordGen.passphrase(words, 5, "-", r)
        assertEquals(5, pp.split("-").size); assertTrue(pp.split("-").all { it in words })
        assertEquals(10.0, PasswordGen.passphraseBits(words, 5), 1e-9)
    }

    @Test fun mailPasswordsOffered() {
        val logins = listOf(SavedLogin("accounts.google.com", "maria@gmail.com", "google-account"), SavedLogin("imap.gmail.com", "maria@gmail.com", "app-password", MailPasswords.NOTE),
            SavedLogin("mail.example.gr", "maria", "school"), SavedLogin("imap.example.gr", "nikos", "not-hers"))
        // Gmail: the app password kept for its server, never Google's account password (another site)
        assertEquals(listOf("app-password"), MailPasswords.candidates(logins, "Maria@gmail.com", "imap.gmail.com").map { it.password })
        // a school's: the user by her name before the @, at the same site as the server
        assertEquals(listOf("school"), MailPasswords.candidates(logins, "maria@example.gr", "imap.example.gr").map { it.password })
        assertEquals(emptyList<SavedLogin>(), MailPasswords.candidates(logins, "maria@example.gr", ""))
    }

    @Test fun effWordList() {
        // the asset as PasswordGen.words reads it: every one of EFF's 7,776 words, each once (12.9 bits a word)
        val words = File("src/main/assets/eff_large_wordlist.txt").readLines().mapNotNull { it.substringAfter('\t', "").trim().takeIf(String::isNotEmpty) }
        assertEquals(7776, words.toSet().size)
        assertEquals(64, PasswordGen.passphraseBits(words, 5).toInt())
    }

    @Test fun twoFactorAcrossDevices() {
        val (r, key) = BrowserPasswords.create("correct horse".toCharArray(), now = 1, m = 1024, t = 1)
        val dirA = tmp.newFolder("a"); val dirB = tmp.newFolder("b")
        val gh = OtpEntry("GitHub", "maria", "JBSWY3DPEHPK3PXP"); val gl = OtpEntry("GitLab", "maria", "GEZDGNBVGY3TQOJQ")
        fun copy(from: File, to: File) = from.listFiles().orEmpty().forEach { it.copyTo(File(to, it.name), overwrite = true) }
        val a = BrowserPasswords.merged(dirA, "2fa", "phone", AuthSync.keyed(listOf(gh)), null, key, r, 10)
        val b = BrowserPasswords.merged(dirB, "2fa", "tablet", AuthSync.keyed(listOf(gl)), null, key, r, 20)
        copy(dirA, dirB); copy(dirB, dirA)
        val a2 = BrowserPasswords.merged(dirA, "2fa", "phone", AuthSync.keyed(listOf(gh)), a.all, key, r, 30)
        assertEquals(listOf(gh, gl), AuthSync.entries(a2.all, listOf(gh)))
        // the tablet deletes GitHub's; the phone hears it
        val b2 = BrowserPasswords.merged(dirB, "2fa", "tablet", AuthSync.keyed(listOf(gl)), b.all.merge(a.all), key, r, 40)
        copy(dirB, dirA)
        val a3 = BrowserPasswords.merged(dirA, "2fa", "phone", AuthSync.keyed(listOf(gh, gl)), a2.all, key, r, 50)
        assertEquals(listOf(gl), AuthSync.entries(a3.all, listOf(gh, gl)))
        assertEquals(b2.all.items.keys, a3.all.items.keys)
        // no secret in the clear, and a passwords file does not open as 2FA (the kind is bound in)
        dirA.listFiles()!!.forEach { assertFalse(it.readText().contains("JBSWY3DP")) }
        val pw = BrowserPasswords.encrypt(key, r, "phone", SecretSet().record(AuthSync.keyed(listOf(gh)), 1))
        assertNull(BrowserPasswords.decrypt(key, r, "phone", pw, "2fa"))
        assertEquals("GitHub", JSONObject(BrowserPasswords.decrypt(key, r, "phone", pw)!!.items.values.single().value).getString("issuer"))
    }
}
