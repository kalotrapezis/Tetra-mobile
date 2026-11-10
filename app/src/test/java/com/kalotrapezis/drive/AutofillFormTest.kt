package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Passwords as Android's autofill (PasswordsAutofill.kt): which field is which, and whose logins a form is offered. */
class AutofillFormTest {
    private fun f(id: String, inputType: Int = 1, hints: List<String> = emptyList(), type: String = "", ac: String = "", words: String = "") =
        AutofillForm.Field(id, hints, type, ac, inputType, words)

    @Test fun anAppsForm() {
        // an app's: a search box, the e-mail (its input type), the password (TYPE_TEXT_VARIATION_PASSWORD), a "remember me"
        val form = AutofillForm.find(listOf(f("search", words = "search_box"), f("mail", 0x21), f("pw", 0x81), f("note", words = "remember")))!!
        assertEquals("mail", form.user!!.id); assertEquals(listOf("pw"), form.passwords.map { it.id })
        // by Android's hints; a number PIN (TYPE_NUMBER_VARIATION_PASSWORD)
        val hinted = AutofillForm.find(listOf(f("u", hints = listOf("username")), f("p", 0x12)))!!
        assertEquals("u", hinted.user!!.id); assertEquals("p", hinted.passwords.single().id)
        // no word for the user: the text field just before the password
        assertEquals("a", AutofillForm.find(listOf(f("a", words = "field1"), f("b", 0x81)))!!.user!!.id)
    }

    @Test fun aWebPage() {
        // a page in a browser: HTML's type and autocomplete; a new password twice (sign-up) both filled
        val form = AutofillForm.find(listOf(f("q", type = "text", words = "q"), f("email", type = "email", ac = "username", words = "email"),
            f("p1", type = "password", ac = "new-password"), f("p2", type = "password")))!!
        assertEquals("email", form.user!!.id); assertEquals(listOf("p1", "p2"), form.passwords.map { it.id })
        // Google's first page: the user alone; a page of nothing to fill
        assertEquals(AutofillForm.Form(f("id", type = "email", words = "identifier email"), emptyList()), AutofillForm.find(listOf(f("id", type = "email", words = "identifier email"))))
        assertNull(AutofillForm.find(listOf(f("q", type = "text", words = "search"))))
    }

    @Test fun whoseLogins() {
        val logins = listOf(SavedLogin("accounts.example.com", "maria", "a"), SavedLogin("example.com", "nikos", "b"),
            SavedLogin("com.example.app", "maria", "c"), SavedLogin("shop.example.gr", "x", "d"))
        // a browser's page: by site, the subdomain's too
        assertEquals(listOf("a", "b"), AutofillForm.matches(logins, "www.example.com", "com.android.chrome").map { it.password })
        assertEquals(listOf("d"), AutofillForm.matches(logins, "shop.example.gr", "org.mozilla.firefox").map { it.password })
        // an app: only what was kept for that very app; its word for a site, or a package like a site's, is never taken
        assertEquals(listOf("c"), AutofillForm.matches(logins, null, "com.example.app").map { it.password })
        assertEquals(emptyList<SavedLogin>(), AutofillForm.matches(logins, "example.com", "com.evil.app"))
        assertEquals(emptyList<SavedLogin>(), AutofillForm.matches(logins, null, "com.example.evil"))
        // what a save is kept as
        assertEquals("accounts.example.com", AutofillForm.hostFor("Accounts.Example.com", "com.android.chrome"))
        assertEquals("com.evil.app", AutofillForm.hostFor("example.com", "com.evil.app"))
    }

    @Test fun aCodesField() {
        fun o(id: String, inputType: Int = 1, hints: List<String> = emptyList(), type: String = "", ac: String = "", words: String = "", max: Int = -1, mode: String = "") =
            AutofillForm.Field(id, hints, type, ac, inputType, words, maxLength = max, inputMode = mode)
        // Android's hints, HTML's one-time-code
        assertTrue(AutofillForm.isOtp(o("a", hints = listOf("smsOTPCode"))))
        assertTrue(AutofillForm.isOtp(o("b", hints = listOf("2faAppOTPCode"))))
        assertTrue(AutofillForm.isOtp(o("c", type = "text", ac = "one-time-code")))
        // by its words: numbers, 6–8 long or not said
        assertTrue(AutofillForm.isOtp(o("d", 2, words = "totp_input", max = 6)))
        assertTrue(AutofillForm.isOtp(o("e", type = "tel", words = "verification code")))
        assertTrue(AutofillForm.isOtp(o("f", type = "text", mode = "numeric", words = "κωδικός")))
        // not: a post code, a text field, a code of 4, a PIN (a password), a user's field
        assertFalse(AutofillForm.isOtp(o("g", 2, words = "postal code")))
        assertFalse(AutofillForm.isOtp(o("h", type = "text", words = "promo code")))
        assertFalse(AutofillForm.isOtp(o("i", 2, words = "sms code", max = 4)))
        assertFalse(AutofillForm.isOtp(o("j", 0x12, words = "pin code")))
        // a code's page: the code's field, not a user's; with a password on it, the login's
        val page = listOf(o("u", type = "email", words = "email"), o("otp", type = "text", ac = "one-time-code"))
        assertEquals("otp", AutofillForm.otp(page)!!.id)
        assertNull(AutofillForm.otp(page + o("p", type = "password")))
        assertNull(AutofillForm.find(listOf(o("otp", type = "text", ac = "one-time-code"))))
    }

    @Test fun whoseCodes() {
        val gh = OtpEntry("GitHub", "maria", "JBSWY3DP"); val g = OtpEntry("Google", "maria@gmail.com", "JBSWY3DP")
        val mail = OtpEntry("", "nikos@proton.me", "JBSWY3DP"); val all = listOf(gh, g, mail)
        // a page by its site's name, an app by its package's words, an account by its e-mail's domain
        assertEquals(listOf(gh), AutofillForm.codesFor(all, "github.com", "com.android.chrome"))
        assertEquals(listOf(g), AutofillForm.codesFor(all, "accounts.google.com", "org.mozilla.firefox"))
        assertEquals(listOf(gh), AutofillForm.codesFor(all, null, "com.github.android"))
        assertEquals(listOf(mail), AutofillForm.codesFor(all, "account.proton.me", "com.android.chrome"))
        // unsure: all of them to choose from; an app's word for a site is not taken
        assertEquals(all, AutofillForm.codesFor(all, "example.com", "com.android.chrome"))
        assertEquals(all, AutofillForm.codesFor(all, "github.com", "com.example.app"))
    }
}
