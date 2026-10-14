package com.wireless.audiostreamer.audio

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Records incoming JPEG frames on the Receiver phone into an .mjpeg file.
 */
class ReceiverVideoWriter(private val ctx: Context) {
    @Volatile private var out: BufferedOutputStream? = null
    @Volatile private var path: String? = null
    @Volatile var isRecording = false
        private set
    private var frames = 0

    fun start(): String? {
        stop()
        return try {
            val dir = File(ctx.getExternalFilesDir(null), "recordings").also { it.mkdirs() }
            val f = File(dir, "cam_${System.currentTimeMillis()}.mjpeg")
            out = BufferedOutputStream(FileOutputStream(f), 64 * 1024)
            path = f.absolutePath
            frames = 0
            isRecording = true
            path
        } catch (_: Exception) {
            isRecording = false
            null
        }
    }

    fun writeJpeg(jpeg: ByteArray) {
        if (!isRecording) return
        try {
            out?.write(jpeg)
            out?.flush()
            frames++
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop(): String? {
        isRecording = false
        try { out?.flush(); out?.close() } catch (_: Exception) {}
        out = null
        val p = path
        path = null
        return if (frames > 0) p else {
            try { p?.let { File(it).delete() } } catch (_: Exception) {}
            null
        }
    }
}
