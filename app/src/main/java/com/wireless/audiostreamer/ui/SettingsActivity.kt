package com.wireless.audiostreamer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.network.NetUtil
import com.wireless.audiostreamer.service.StreamingService
import com.wireless.audiostreamer.util.AppPrefs

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.menu_settings)

        val rbSender = findViewById<RadioButton>(R.id.rbSender)
        val rbReceiver = findViewById<RadioButton>(R.id.rbReceiver)
        val etPin = findViewById<EditText>(R.id.etPin)
        val rbMono = findViewById<RadioButton>(R.id.rbMono)
        val rbStereo = findViewById<RadioButton>(R.id.rbStereo)
        val rbLow = findViewById<RadioButton>(R.id.rbLow)
        val rbNormal = findViewById<RadioButton>(R.id.rbNormal)
        val rbSafe = findViewById<RadioButton>(R.id.rbSafe)
        val etManualIp = findViewById<EditText>(R.id.etManualIp)
        val rbLangSystem = findViewById<RadioButton>(R.id.rbLangSystem)
        val rbLangEn = findViewById<RadioButton>(R.id.rbLangEn)
        val rbLangUr = findViewById<RadioButton>(R.id.rbLangUr)

        val oldLang = AppPrefs.lang(this)

        if (AppPrefs.role(this) == AppPrefs.ROLE_RECEIVER) rbReceiver.isChecked = true else rbSender.isChecked = true
        etPin.setText(AppPrefs.pin(this))
        if (AppPrefs.channels(this) == 2) rbStereo.isChecked = true else rbMono.isChecked = true
        when (AppPrefs.latency(this)) {
            AppPrefs.LAT_LOW -> rbLow.isChecked = true
            AppPrefs.LAT_SAFE -> rbSafe.isChecked = true
            else -> rbNormal.isChecked = true
        }
        etManualIp.setText(AppPrefs.manualIp(this))
        when (oldLang) {
            "en" -> rbLangEn.isChecked = true
            "ur" -> rbLangUr.isChecked = true
            else -> rbLangSystem.isChecked = true
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val pin = etPin.text.toString().trim()
            if (pin.length !in 4..8 || !pin.all { it.isDigit() }) {
                Toast.makeText(this, R.string.pin_invalid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val ip = etManualIp.text.toString().trim()
            if (ip.isNotEmpty() && !NetUtil.isPrivateIp(ip)) {
                Toast.makeText(this, R.string.invalid_ip, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val newLang = when {
                rbLangEn.isChecked -> "en"
                rbLangUr.isChecked -> "ur"
                else -> "system"
            }
            AppPrefs.setRole(this, if (rbReceiver.isChecked) AppPrefs.ROLE_RECEIVER else AppPrefs.ROLE_SENDER)
            AppPrefs.setPin(this, pin)
            AppPrefs.setChannels(this, if (rbStereo.isChecked) 2 else 1)
            AppPrefs.setLatency(
                this,
                when {
                    rbLow.isChecked -> AppPrefs.LAT_LOW
                    rbSafe.isChecked -> AppPrefs.LAT_SAFE
                    else -> AppPrefs.LAT_NORMAL
                }
            )
            AppPrefs.setManualIp(this, ip)
            AppPrefs.setLang(this, newLang)

            if (StreamingService.running) {
                stopService(Intent(this, StreamingService::class.java))
                Toast.makeText(this, R.string.restart_hint, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }

            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
