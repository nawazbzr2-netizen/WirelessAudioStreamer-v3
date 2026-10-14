package com.wireless.audiostreamer.audio

import java.util.TreeMap

/** Re-orders frames by sequence number and absorbs network jitter. */
class JitterBuffer(@Volatile var targetFrames: Int) {
    private val map = TreeMap<Int, ShortArray>()
    private var nextSeq = 0
    private var primed = false

    @Volatile
    var lost = 0
        private set

    @Synchronized
    fun put(seq: Int, pcm: ShortArray) {
        if (primed && seq < nextSeq) return // too late
        map[seq] = pcm
        while (map.size > MAX_FRAMES) map.pollFirstEntry()
    }

    /** Returns the next frame, or null when nothing should be played (lost frame / still buffering). */
    @Synchronized
    fun poll(): ShortArray? {
        if (!primed) {
            if (map.size < targetFrames) return null
            primed = true
            nextSeq = map.firstKey()
        }
        val f = map.remove(nextSeq)
        if (f != null) {
            nextSeq++
            return f
        }
        if (map.isEmpty()) {
            primed = false // under-run: buffer again
            return null
        }
        val first = map.firstKey()
        if (first - nextSeq > MAX_GAP) { // big gap: resync
            nextSeq = first + 1
            return map.remove(first)
        }
        nextSeq++
        lost++
        return null
    }

    @Synchronized
    fun isPrimed(): Boolean = primed

    @Synchronized
    fun reset() {
        map.clear()
        primed = false
    }

    private companion object {
        const val MAX_FRAMES = 50
        const val MAX_GAP = 10
    }
}
