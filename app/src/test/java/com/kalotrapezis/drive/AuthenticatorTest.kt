package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

/** 2FA (Authenticator.kt): the codes against RFC 6238's own table, otpauth:// both ways, Google Authenticator's export. */
class AuthenticatorTest {
    private fun e(seed: String, alg: String) = OtpEntry("", "", Totp.base32(seed.toByteArray()), alg, 8, 30)

    @Test fun rfc6238() {
        val sha1 = e("12345678901234567890", "SHA1")
        val sha256 = e("12345678901234567890123456789012", "SHA256")
        val sha512 = e("1234567890123456789012345678901234567890123456789012345678901234", "SHA512")
        listOf(
            59L to listOf("94287082", "46119246", "90693936"),
            1111111109L to listOf("07081804", "68084774", "25091201"),
            1111111111L to listOf("14050471", "67062674", "99943326"),
            1234567890L to listOf("89005924", "91819424", "93441116"),
            2000000000L to listOf("69279037", "90698825", "38618901"),
            20000000000L to listOf("65353130", "77737706", "47863826"),
        ).forEach { (t, want) ->
            assertEquals("$t", want, listOf(sha1, sha256, sha512).map { Totp.code(it, t * 1000) })
        }
        // six digits are the last six of the same number; and the seconds left
        assertEquals("287082", Totp.code(sha1.copy(digits = 6), 59_000))
        assertEquals(1, Totp.left(sha1, 59_000)); assertEquals(30, Totp.left(sha1, 60_000))
        assertEquals("287 082", Totp.grouped("287082")); assertEquals("9428 7082", Totp.grouped("94287082"))
    }

    @Test fun base32() {
        // RFC 4648 §10, and what sites print: lower case, spaces, padding
        assertEquals("MZXW6YTBOI", Totp.base32("foobar".toByteArray()))
        assertEquals("foobar", Totp.base32("mzxw 6ytb oi======")!!.decodeToString())
        assertNull(Totp.base32("ABC1")); assertNull(Totp.base32("  "))
    }

    @Test fun otpauth() {
        val a = OtpUri.totp("otpauth://totp/ACME%20Co:jo%40example.org?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60")!!
        assertEquals(OtpEntry("ACME Co", "jo@example.org", "HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", "SHA256", 8, 60), a)
        assertEquals(a, OtpUri.totp(OtpUri.uri(a)))
        // the issuer only in the label; defaults; a secret with spaces
        assertEquals(OtpEntry("Gitea", "maria", "JBSWY3DPEHPK3PXP"), OtpUri.totp("otpauth://totp/Gitea:maria?secret=jbsw%20y3dp%20ehpk%203pxp"))
        assertEquals(OtpEntry("", "solo", "JBSWY3DPEHPK3PXP"), OtpUri.totp("otpauth://totp/solo?secret=JBSWY3DPEHPK3PXP"))
        // GNOME Authenticator's text backup (FreeOTP+'s): the label all encoded, an empty issuer, or no account at all
        assertEquals(OtpEntry("", "jo.m@example.org", "JBSWY3DPEHPK3PXP"), OtpUri.totp("otpauth://totp/jo%2Em%40example%2Eorg?secret=JBSWY3DPEHPK3PXP&issuer=&algorithm=SHA1&digits=6&period=30"))
        assertEquals(OtpEntry("Gitea", "", "JBSWY3DPEHPK3PXP"), OtpUri.totp("otpauth://totp/?secret=JBSWY3DPEHPK3PXP&issuer=Gitea&algorithm=SHA1&digits=6&period=30"))
        assertEquals(2, OtpUri.lines("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP\r\n\notpauth://totp/b?secret=JBSWY3DPEHPK3PXP\nnot a link\n")!!.entries.size)
        // HOTP, no secret, not Base32: not kept
        assertNull(OtpUri.totp("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=1"))
        assertNull(OtpUri.totp("otpauth://totp/x?issuer=y"))
        assertNull(OtpUri.totp("otpauth://totp/x?secret=1111"))
        assertEquals(1, OtpUri.parse("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP")!!.skipped)
        assertNull(OtpUri.parse("https://example.org"))
    }

    // a protobuf written by hand, as Google Authenticator writes its export
    private fun varint(o: ByteArrayOutputStream, v: Long) { var x = v; while (x >= 0x80) { o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
    private fun bytes(o: ByteArrayOutputStream, n: Int, b: ByteArray) { varint(o, (n shl 3 or 2).toLong()); varint(o, b.size.toLong()); o.write(b) }
    private fun num(o: ByteArrayOutputStream, n: Int, v: Long) { varint(o, (n shl 3).toLong()); varint(o, v) }
    private fun otp(secret: ByteArray, name: String, issuer: String, alg: Long, digits: Long, type: Long) = ByteArrayOutputStream().also {
        bytes(it, 1, secret); bytes(it, 2, name.toByteArray()); bytes(it, 3, issuer.toByteArray()); num(it, 4, alg); num(it, 5, digits); num(it, 6, type)
    }.toByteArray()

    @Test fun googleMigration() {
        val p = ByteArrayOutputStream()
        bytes(p, 1, otp("12345678901234567890".toByteArray(), "Example:ana@example.org", "Example", 1, 1, 2))
        bytes(p, 1, otp(byteArrayOf(1, 2, 3, 4, 5), "Κώστας", "", 2, 2, 2))
        bytes(p, 1, otp(byteArrayOf(9, 9, 9), "counter", "Old", 1, 1, 1)) // HOTP
        num(p, 2, 1); num(p, 3, 1); num(p, 4, 0); num(p, 5, 123456789)
        val uri = "otpauth-migration://offline?data=" + URLEncoder.encode(Base64.getEncoder().encodeToString(p.toByteArray()), "UTF-8")
        val r = OtpUri.parse(uri)!!
        assertEquals(listOf(
            OtpEntry("Example", "ana@example.org", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"),
            OtpEntry("", "Κώστας", "AEBAGBAF", "SHA256", 8)), r.entries)
        assertEquals(1, r.skipped)
        assertEquals("287082", Totp.code(r.entries[0], 59_000))
        assertNull(OtpUri.parse("otpauth-migration://offline?data=%%%"))
    }

    @Test fun mergeKeepsOneOfEach() {
        val a = OtpEntry("GitHub", "maria", "JBSWY3DPEHPK3PXP")
        val (all, new) = AuthSecrets.merge(listOf(a), listOf(a.copy(issuer = "github", secret = "jbsw y3dp ehpk 3pxp"), OtpEntry("GitLab", "maria", "JBSWY3DPEHPK3PXP")))
        assertEquals(2, all.size); assertEquals(1, new)
    }
}
