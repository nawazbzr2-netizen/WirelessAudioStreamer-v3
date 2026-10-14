package com.wireless.audiostreamer.util

import android.content.Context
import android.os.Build

object AppPrefs {
    const val ROLE_SENDER = "SENDER"
    const val ROLE_RECEIVER = "RECEIVER"
    const val LAT_LOW = 0
    const val LAT_NORMAL = 1
    const val LAT_SAFE = 2

    private const val FILE = "was_prefs_v2"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun role(c: Context): String = p(c).getString("role", "") ?: ""
    fun setRole(c: Context, v: String) = p(c).edit().putString("role", v).apply()

    fun pin(c: Context): String = p(c).getString("pin", "") ?: ""
    fun setPin(c: Context, v: String) = p(c).edit().putString("pin", v).apply()

    /** 1 = mono, 2 = stereo */
    fun channels(c: Context): Int = p(c).getInt("channels", 1)
    fun setChannels(c: Context, v: Int) = p(c).edit().putInt("channels", v).apply()

    fun latency(c: Context): Int = p(c).getInt("latency", LAT_NORMAL)
    fun setLatency(c: Context, v: Int) = p(c).edit().putInt("latency", v).apply()

    fun manualIp(c: Context): String = p(c).getString("manual_ip", "") ?: ""
    fun setManualIp(c: Context, v: String) = p(c).edit().putString("manual_ip", v).apply()

    /** "system", "en" or "ur" */
    fun lang(c: Context): String = p(c).getString("lang", "system") ?: "system"
    fun setLang(c: Context, v: String) = p(c).edit().putString("lang", v).apply()

    fun isConfigured(c: Context): Boolean {
        val r = role(c)
        val p = pin(c)
        return (r == ROLE_SENDER || r == ROLE_RECEIVER) &&
            p.length in 4..8 && p.all { it.isDigit() }
    }

    /** Number of 20 ms frames the receiver buffers before playing. */
    fun targetFrames(c: Context): Int = when (latency(c)) {
        LAT_LOW -> 2
        LAT_SAFE -> 8
        else -> 4
    }

    fun deviceName(): String = (Build.MODEL ?: "Android").take(40)

    // --- Recent connection (auto-reconnect) ---
    fun lastPeerIp(c: Context): String = p(c).getString("last_peer_ip", "") ?: ""
    fun lastMode(c: Context): String = p(c).getString("last_mode", "WIFI") ?: "WIFI"
    fun lastBtAddr(c: Context): String = p(c).getString("last_bt", "") ?: ""
    fun lastStream(c: Context): String = p(c).getString("last_stream", "") ?: ""

    fun saveRecentConnection(c: Context, mode: String, peerIp: String?, btAddr: String?, stream: String) {
        p(c).edit()
            .putString("last_mode", mode)
            .putString("last_peer_ip", peerIp ?: "")
            .putString("last_bt", btAddr ?: "")
            .putString("last_stream", stream)
            .apply()
    }

    fun clearRecentConnection(c: Context) {
        p(c).edit()
            .remove("last_mode").remove("last_peer_ip")
            .remove("last_bt").remove("last_stream")
            .apply()
    }
}
