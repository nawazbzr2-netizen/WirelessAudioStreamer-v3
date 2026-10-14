package com.wireless.audiostreamer.audio

/**
 * IMA ADPCM (4:1). Every channel block is self-contained (3-byte header with predictor + index),
 * so a lost packet never corrupts the following ones.
 */
object AdpcmCodec {
    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253,
        279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166,
        1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026,
        4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )
    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    /** Bytes used by one channel block of [n] samples. */
    fun blockSize(n: Int): Int = 3 + n / 2

    /** Encodes samples pcm[offset + i*stride], i in 0 until n. Returns the new write position. */
    fun encode(pcm: ShortArray, offset: Int, stride: Int, n: Int, out: ByteArray, pos: Int): Int {
        var pred = pcm[offset].toInt()
        var index = 0
        out[pos] = (pred and 0xFF).toByte()
        out[pos + 1] = ((pred shr 8) and 0xFF).toByte()
        out[pos + 2] = index.toByte()
        var p = pos + 3
        var half = false
        var cur = 0
        for (i in 1 until n) {
            val s = pcm[offset + i * stride].toInt()
            var step = STEP[index]
            var diff = s - pred
            var code = 0
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            var vp = step shr 3
            if (diff >= step) { code = code or 4; diff -= step; vp += step }
            step = step shr 1
            if (diff >= step) { code = code or 2; diff -= step; vp += step }
            step = step shr 1
            if (diff >= step) { code = code or 1; vp += step }
            pred += if ((code and 8) != 0) -vp else vp
            if (pred > 32767) pred = 32767 else if (pred < -32768) pred = -32768
            index += INDEX[code and 7]
            if (index < 0) index = 0 else if (index > 88) index = 88
            if (!half) {
                cur = code
                half = true
            } else {
                out[p++] = (cur or (code shl 4)).toByte()
                half = false
            }
        }
        if (half) out[p++] = cur.toByte()
        return p
    }

    /** Decodes one block into dst[offset + i*stride]. Returns the new read position. */
    fun decode(src: ByteArray, pos: Int, n: Int, dst: ShortArray, offset: Int, stride: Int): Int {
        var pred = (src[pos + 1].toInt() shl 8) or (src[pos].toInt() and 0xFF)
        var index = src[pos + 2].toInt()
        if (index < 0) index = 0 else if (index > 88) index = 88
        dst[offset] = pred.toShort()
        var p = pos + 3
        var half = false
        var cur = 0
        for (i in 1 until n) {
            val code: Int
            if (!half) {
                cur = src[p++].toInt() and 0xFF
                code = cur and 0xF
                half = true
            } else {
                code = (cur shr 4) and 0xF
                half = false
            }
            val step = STEP[index]
            var vp = step shr 3
            if ((code and 4) != 0) vp += step
            if ((code and 2) != 0) vp += step shr 1
            if ((code and 1) != 0) vp += step shr 2
            pred += if ((code and 8) != 0) -vp else vp
            if (pred > 32767) pred = 32767 else if (pred < -32768) pred = -32768
            index += INDEX[code and 7]
            if (index < 0) index = 0 else if (index > 88) index = 88
            dst[offset + i * stride] = pred.toShort()
        }
        return p
    }
}
