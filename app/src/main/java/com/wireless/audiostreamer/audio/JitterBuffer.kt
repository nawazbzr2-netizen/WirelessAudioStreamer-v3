package com.wireless.audiostreamer.audio

import java.util.TreeMap

class JitterBuffer(@Volatile var targetFrames: Int) {
    private val map = TreeMap<Int, ShortArray>()
    private var nextSeq = 0
    private var primed = false

    @Volatile
    var lost = 0
        private set

    @Synchronized
    fun put(seq: Int, pcm: ShortArray) {
        if (primed && seq < nextSeq) return
        map[seq] = pcm
        while (map.size > MAX_FRAMES) map.pollFirstEntry()
    }

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
            primed = false
            return null
        }
        val first = map.firstKey()
        if (first - nextSeq > MAX_GAP) {
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
