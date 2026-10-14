package com.wireless.audiostreamer.network

import com.wireless.audiostreamer.audio.AdpcmCodec

object Protocol {
    const val PORT_DISCOVERY = 45454
    const val PORT_AUDIO = 45455
    const val PORT_FILE = 45456
    const val PORT_CAMERA = 45457
    const val PORT_CTRL = 45458
    const val T_AUDIO = 1
    const val T_HELLO = 2
    const val T_CAMERA = 3
    const val T_FILE = 4
    const val T_CTRL = 5

    const val STREAM_SYSTEM = "SYSTEM"
    const val STREAM_MIC = "MIC"
    const val STREAM_CAMERA_FRONT = "CAM_FRONT"
    const val STREAM_CAMERA_BACK = "CAM_BACK"
    const val STREAM_STORAGE = "STORAGE"
    const val STREAM_COMBO = "COMBO"
    const val SAMPLE_RATE = 48000
    const val SAMPLES = 960

    class Audio(val channels: Int, val pcm: ShortArray)

    fun buildAudioPlain(pcm: ShortArray, channels: Int): ByteArray {
        val block = AdpcmCodec.blockSize(SAMPLES)
        val out = ByteArray(3 + channels * block)
        out[0] = channels.toByte()
        out[1] = (SAMPLES shr 8).toByte()
        out[2] = (SAMPLES and 0xFF).toByte()
        var pos = 3
        for (ch in 0 until channels) {
            pos = AdpcmCodec.encode(pcm, ch, channels, SAMPLES, out, pos)
        }
        return out
    }

    fun parseAudioPlain(b: ByteArray): Audio? {
        if (b.size < 3) return null
        val ch = b[0].toInt()
        val n = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        if (ch < 1 || ch > 2 || n != SAMPLES) return null
        val block = AdpcmCodec.blockSize(n)
        if (b.size < 3 + ch * block) return null
        val pcm = ShortArray(n * ch)
        var pos = 3
        for (c in 0 until ch) {
            pos = AdpcmCodec.decode(b, pos, n, pcm, c, ch)
        }
        return Audio(ch, pcm)
    }

    fun helloPlain(name: String, role: String, stream: String = ""): ByteArray =
        "$name\n$role\n$stream".toByteArray(Charsets.UTF_8)

    fun parseHello(b: ByteArray): Triple<String, String, String>? {
        val s = String(b, Charsets.UTF_8)
        val parts = s.split('\n', limit = 3)
        if (parts.size < 2) return null
        val name = parts[0].take(40)
        val role = parts[1]
        val stream = if (parts.size > 2) parts[2] else ""
        return Triple(name, role, stream)
    }
}
