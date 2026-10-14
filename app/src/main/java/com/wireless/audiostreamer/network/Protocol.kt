package com.wireless.audiostreamer.network

import com.wireless.audiostreamer.audio.AdpcmCodec

object Protocol {
    const val PORT_DISCOVERY = 45454
    const val PORT_AUDIO = 45455
    const val T_AUDIO = 1
    const val T_HELLO = 2
    const val T_CAMERA = 3
    const val T_FILE = 4

    // Feature stream kinds (carried in app layer / service extra)
    const val STREAM_SYSTEM = "SYSTEM"
    const val STREAM_MIC = "MIC"
    const val STREAM_CAMERA_FRONT = "CAM_FRONT"
    const val STREAM_CAMERA_BACK = "CAM_BACK"
    const val STREAM_STORAGE = "STORAGE"
    const val STREAM_COMBO = "COMBO"
    const val SAMPLE_RATE = 48000
    const val SAMPLES = 960 // 20 ms @ 48 kHz, per channel

    class Audio(val channels: Int, val pcm: ShortArray)

    /** pcm = interleaved samples, size SAMPLES * channels. */
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

    fun helloPlain(name: String, role: String): ByteArray =
        "$name\n$role".toByteArray(Charsets.UTF_8)

    /** Returns (name, role) or null. */
    fun parseHello(b: ByteArray): Pair<String, String>? {
        val s = String(b, Charsets.UTF_8)
        val i = s.lastIndexOf('\n')
        if (i < 0) return null
        return Pair(s.substring(0, i).take(40), s.substring(i + 1))
    }
}
