package com.wireless.audiostreamer.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records microphone only (not system audio). Auto-saves to Music/WAS on stop. */
class MicRecorder(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var outUri: Uri? = null

    fun start(): Boolean {
        stop()
        return try {
            val name = "WAS_MIC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
            val rec: MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/WAS")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                outUri = uri
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: return false
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(44100)
                rec.setAudioEncodingBitRate(128000)
                rec.setOutputFile(pfd.fileDescriptor)
                rec.prepare()
                rec.start()
                recorder = rec
                true
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WAS")
                dir.mkdirs()
                val f = File(dir, name)
                outFile = f
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(44100)
                rec.setAudioEncodingBitRate(128000)
                rec.setOutputFile(f.absolutePath)
                rec.prepare()
                rec.start()
                recorder = rec
                true
            }
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop(): String? {
        val r = recorder
        recorder = null
        var result: String? = null
        try { r?.stop() } catch (_: Exception) { }
        try { r?.release() } catch (_: Exception) { }
        outUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, values, null, null)
            }
            result = uri.toString()
            outUri = null
        }
        outFile?.let {
            result = it.absolutePath
            outFile = null
        }
        return result
    }

    val isRecording: Boolean get() = recorder != null
}
