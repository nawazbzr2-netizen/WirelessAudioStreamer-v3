package com.wireless.audiostreamer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.util.AppPrefs

/** First-run screen: choose the role of this phone and the shared PIN. */
class RoleSetupActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppPrefs.isConfigured(this)) {
            openMain()
            return
        }
        setContentView(R.layout.activity_role_setup)
        val rbSender = findViewById<RadioButton>(R.id.rbSender)
        val etPin = findViewById<EditText>(R.id.etPin)
        findViewById<RadioButton>(R.id.rbReceiver).isChecked = false
        rbSender.isChecked = true

        findViewById<Button>(R.id.btnContinue).setOnClickListener {
            val pin = etPin.text.toString().trim()
            // Security: shared code must be 4–8 digits only; both phones need the same code
            if (pin.length !in 4..8 || !pin.all { it.isDigit() }) {
                Toast.makeText(this, R.string.pin_invalid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            AppPrefs.setPin(this, pin)
            AppPrefs.setRole(this, if (rbSender.isChecked) AppPrefs.ROLE_SENDER else AppPrefs.ROLE_RECEIVER)
            openMain()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
