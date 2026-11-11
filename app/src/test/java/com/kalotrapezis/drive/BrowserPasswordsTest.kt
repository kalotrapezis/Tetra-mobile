package com.kalotrapezis.drive

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Passwords between devices, encrypted (BrowserPasswords.kt). Argon2id kept small here (1 MiB) so the tests are quick. */
class BrowserPasswordsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val secret = "correct horse battery staple".toCharArray()
    private fun recipe() = BrowserPasswords.create(secret, now = 1_000, m = 1024, t = 1)

    @Test fun argon2idIsArgon2id() {
        // RFC 9106 §5.3, Argon2id: the library and the settings Tetra builds (type id, version 1.3) give the RFC's tag
        val g = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        g.init(org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
            .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13).withSalt(ByteArray(16) { 2 }).withSecret(ByteArray(8) { 3 })
            .withAdditional(ByteArray(12) { 4 }).withMemoryAsKB(32).withIterations(3).withParallelism(4).build())
        val tag = ByteArray(32).also { g.generateBytes(ByteArray(32) { 1 }, it) }
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", tag.joinToString("") { "%02x".format(it) })
        // and the same passphrase and salt make the same key, another salt or passphrase another
        val (r, key) = recipe()
        assertArrayEquals(key, BrowserPasswords.derive(secret, r))
        assertFalse(key.contentEquals(BrowserPasswords.derive("correct horse battery stapler".toCharArray(), r)))
        assertFalse(key.contentEquals(BrowserPasswords.derive(secret, r.copy(salt = ByteArray(16)))))
        assertEquals(32, key.size)
    }

    @Test fun roundTripAndAWrongPassphraseIsRefused() {
        val (r, key) = recipe()
        val back = BrowserPasswords.parseRecipe(r.json())!!
        assertArrayEquals(key, BrowserPasswords.unlock(secret, back))
        assertTrue(runCatching { BrowserPasswords.unlock("not the passphrase".toCharArray(), back) }.exceptionOrNull() is BrowserPasswords.WrongPassphrase)
        val set = SecretSet().record(BrowserVault(listOf(SavedLogin("a.gr", "teo", "s3cret!"))), 5)
        val text = BrowserPasswords.encrypt(key, r, "phone", set)
        assertEquals(set, BrowserPasswords.decrypt(key, back, "phone", text))
        assertNull(BrowserPasswords.decrypt(BrowserPasswords.derive("wrong one!".toCharArray(), r), r, "phone", text))
        assertNull(BrowserPasswords.decrypt(key, r, "tablet", text)) // bound to the device whose file it is
        // a fresh nonce each time: the same set never makes the same file
        assertFalse(text == BrowserPasswords.encrypt(key, r, "phone", set))
    }

    @Test fun aTamperedFileIsRefused() {
        val (r, key) = recipe()
        val set = SecretSet().record(BrowserVault(listOf(SavedLogin("a.gr", "teo", "s3cret!"))), 5)
        val o = JSONObject(BrowserPasswords.encrypt(key, r, "phone", set))
        val data = java.util.Base64.getDecoder().decode(o.getString("data"))
        data[data.size / 2] = (data[data.size / 2].toInt() xor 1).toByte()
        o.put("data", java.util.Base64.getEncoder().encodeToString(data))
        assertNull(BrowserPasswords.decrypt(key, r, "phone", o.toString()))
        // and a recipe whose check box was changed refuses even the right passphrase
        assertTrue(runCatching { BrowserPasswords.unlock(secret, r.copy(check = recipe().first.check)) }.exceptionOrNull() is BrowserPasswords.WrongPassphrase)
    }

    /** A device: its folder, its vault, its base (the app's keystore storage on a phone). */
    private inner class Device(val id: String, var vault: BrowserVault = BrowserVault()) {
        val root: File = tmp.newFolder(id)
        var base: SecretSet? = null
        fun sync(key: ByteArray, r: BrowserPasswords.Recipe, now: Long) { BrowserPasswords.sync(root, id, vault, base, key, r, now).let { vault = it.vault; base = it.base } }
        fun from(o: Device) = File(o.root, BrowserPasswords.DIR).listFiles().orEmpty().filter { it.name != "$id.enc" }
            .forEach { it.copyTo(File(root, "${BrowserPasswords.DIR}/${it.name}"), overwrite = true) }
    }

    @Test fun mergeAcrossDevices() {
        val (r, key) = recipe()
        val phone = Device("phone", BrowserVault(listOf(SavedLogin("a.gr", "teo", "one"), SavedLogin("b.gr", "teo", "two")),
            listOf(mapOf("name" to "Teo", "email" to "t@x.gr")), listOf(SavedCard("TEO", "4111111111111111", "01", "30"))))
        val tablet = Device("tablet", BrowserVault(listOf(SavedLogin("c.gr", "me", "three"), SavedLogin("a.gr", "teo", "one-tablet"))))
        BrowserPasswords.writeRecipe(phone.root, r)
        phone.sync(key, r, 1_000); tablet.sync(key, r, 2_000)
        fun both(now: Long) { tablet.from(phone); phone.from(tablet); phone.sync(key, r, now); tablet.sync(key, r, now); tablet.from(phone); phone.from(tablet) }
        both(3_000)
        // first sync: the union, a login once by site and user (the later first sync's password)
        assertEquals(setOf("a.gr|teo|one-tablet", "b.gr|teo|two", "c.gr|me|three"), phone.vault.logins.map { "${it.host}|${it.user}|${it.password}" }.toSet())
        assertEquals(phone.vault.logins.toSet(), tablet.vault.logins.toSet())
        assertEquals(1, tablet.vault.addresses.size); assertEquals("4111111111111111", tablet.vault.cards.single().number)
        // concurrent: the phone changes a.gr's password, the tablet later deletes it and changes b.gr's; the phone then deletes the card
        phone.vault = phone.vault.copy(logins = phone.vault.logins.map { if (it.host == "a.gr") it.copy(password = "new") else it }); phone.sync(key, r, 4_000)
        tablet.vault = tablet.vault.copy(logins = tablet.vault.logins.filter { it.host != "a.gr" }.map { if (it.host == "b.gr") it.copy(password = "two-2") else it }); tablet.sync(key, r, 5_000)
        phone.vault = phone.vault.copy(cards = emptyList()); phone.sync(key, r, 6_000)
        both(7_000)
        for (d in listOf(phone, tablet)) {
            assertEquals(setOf("b.gr|teo|two-2", "c.gr|me|three"), d.vault.logins.map { "${it.host}|${it.user}|${it.password}" }.toSet())
            assertTrue(d.vault.cards.isEmpty())
            assertEquals(mapOf("name" to "Teo", "email" to "t@x.gr"), d.vault.addresses.single())
        }
    }

    @Test fun anotherPassphrasesFileIsLeftNotRead() {
        val (r, key) = recipe()
        val (r2, key2) = BrowserPasswords.create("another passphrase".toCharArray(), now = 2_000, m = 1024, t = 1)
        val phone = Device("phone", BrowserVault(listOf(SavedLogin("a.gr", "teo", "one"))))
        val tablet = Device("tablet", BrowserVault(listOf(SavedLogin("t.gr", "x", "y"))))
        phone.sync(key, r, 1_000); tablet.sync(key2, r2, 1_000)
        phone.from(tablet)
        val res = BrowserPasswords.sync(phone.root, "phone", phone.vault, phone.base, key, r, 2_000)
        assertEquals(1, res.unreadable)
        assertEquals(listOf("a.gr"), res.vault.logins.map { it.host })
        // the recipe in force is the one made last (forgot it: a new key.json; a sync copy "key (2).json" beside it)
        BrowserPasswords.writeRecipe(phone.root, r)
        BrowserSync.save(File(phone.root, "${BrowserPasswords.DIR}/key (2).json"), r2.json())
        assertEquals(r2.id, BrowserPasswords.recipe(phone.root)!!.id)
    }

    @Test fun noPlaintextInTheSharedFolder() {
        val (r, key) = recipe()
        val phone = Device("phone", BrowserVault(listOf(SavedLogin("bank.example.gr", "teo@example.gr", "Hunter2-unique-PW")),
            listOf(mapOf("street-address" to "Odos Unique 42")), listOf(SavedCard("TEO K", "4111111111111111", "01", "30"))))
        BrowserPasswords.writeRecipe(phone.root, r)
        phone.sync(key, r, 1_000)
        val files = phone.root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.any { it.name == "phone.enc" } && files.any { it.name == "key.json" })
        for (f in files) {
            val bytes = f.readBytes().decodeToString()
            for (plain in listOf("Hunter2-unique-PW", "teo@example.gr", "bank.example.gr", "Odos Unique", "4111111111111111", "TEO K", "correct horse"))
                assertFalse("$plain in ${f.name}", bytes.contains(plain))
        }
        assertTrue(files.none { it.name.endsWith(".part") }) // nothing half-written left either
    }

    @Test fun tombstonesGoAfter30Days() {
        val s = SecretSet().record(BrowserVault(listOf(SavedLogin("a.gr", "u", "p"))), 1).record(BrowserVault(), 2)
        assertTrue(s.items.values.single().gone)
        assertTrue(s.pruned(2 + 31 * 86_400_000L).items.isEmpty())
        assertEquals(s, SecretSet.parse(s.json()))
    }

    @Test fun aCardsSecurityCodeFromTheComputerIsKept() {
        // the computer writes a card's code fifth; the phone reads it, writes it back, and a page's card keeps it
        val set = SecretSet(mapOf("c|5500000000000004" to SecretRec("""["MARIA","5500000000000004","12","31","123"]""", 1_000)))
        val v = set.vault()
        assertEquals("123", v.cards.single().cvc)
        assertEquals("""["MARIA","5500000000000004","12","31","123"]""", SecretSet.keyed(v).getValue("c|5500000000000004"))
        assertEquals("""["B","4111111111111111","01","30"]""", SecretSet.keyed(BrowserVault(cards = listOf(SavedCard("B", "4111111111111111", "01", "30")))).getValue("c|4111111111111111"))
        val fromPage = v.with(BrowserVault(cards = listOf(SavedCard("MARIA K", "5500000000000004", "12", "31"))))
        assertEquals(SavedCard("MARIA K", "5500000000000004", "12", "31", "123"), fromPage.cards.single())
        assertEquals("123", BrowserVault.of(fromPage.json()).cards.single().cvc) // the browser's own file too
    }
}
