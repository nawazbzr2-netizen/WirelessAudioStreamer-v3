package com.wireless.audiostreamer.network

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for every packet. Key = PBKDF2(PIN).
 * Header (13 bytes, authenticated): type(1) | session(8) | seq(4). IV = session|seq (12 bytes).
 * Packets that were not encrypted with the same PIN are silently rejected.
 */
class PacketCipher(pin: String) {
    class Opened(val type: Int, val session: Long, val seq: Int, val plain: ByteArray)

    private val key: SecretKeySpec

    init {
        val spec = PBEKeySpec(pin.toCharArray(), SALT, 20000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        key = SecretKeySpec(raw, "AES")
    }

    fun seal(type: Int, session: Long, seq: Int, plain: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(HEADER).put(type.toByte()).putLong(session).putInt(seq).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, h, 1, 12))
        c.updateAAD(h)
        val ct = c.doFinal(plain)
        val out = ByteArray(HEADER + ct.size)
        System.arraycopy(h, 0, out, 0, HEADER)
        System.arraycopy(ct, 0, out, HEADER, ct.size)
        return out
    }

    fun open(buf: ByteArray, len: Int): Opened? {
        if (len < HEADER + 16) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, buf, 1, 12))
            c.updateAAD(buf, 0, HEADER)
            val plain = c.doFinal(buf, HEADER, len - HEADER)
            val bb = ByteBuffer.wrap(buf, 0, HEADER)
            val type = bb.get().toInt()
            val session = bb.getLong()
            val seq = bb.getInt()
            Opened(type, session, seq, plain)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val HEADER = 13
        val SALT = "WirelessAudioStreamer-v2".toByteArray(Charsets.UTF_8)
    }
}
