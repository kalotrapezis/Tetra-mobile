package com.kalotrapezis.drive

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Another app's sign-in form (it lives in the test package, com.kalotrapezis.drive.test), so Passwords' autofill can be
 * tried on an emulator by hand: `am start -n com.kalotrapezis.drive.test/com.kalotrapezis.drive.AutofillProbe`. Sign in
 * leaves the form, as a real one does, which is when Android offers to save.
 */
class AutofillProbe : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = EditText(this).apply { hint = "Email"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setAutofillHints(View.AUTOFILL_HINT_USERNAME) }
        val pass = EditText(this).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setAutofillHints(View.AUTOFILL_HINT_PASSWORD) }
        val done = TextView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 200, 48, 48)
            addView(user); addView(pass)
            addView(Button(this@AutofillProbe).apply { text = "Sign in"; setOnClickListener { done.text = "Signed in as ${user.text}"; finish() } })
            addView(done)
        })
    }
}
