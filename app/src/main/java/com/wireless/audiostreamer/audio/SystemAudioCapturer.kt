package com.wireless.audiostreamer.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import com.wireless.audiostreamer.network.Protocol
import kotlin.concurrent.thread

/**
 * Captures the phone's internal playback audio (AudioPlaybackCapture, Android 10+).
 * Always captures stereo and down-mixes in software when mono is requested.
 * Requires RECORD_AUDIO to be granted and a live MediaProjection.
 */
class SystemAudioCapturer(
    private val projection: MediaProjection,
    private val outChannels: Int,
    private val onFrame: (ShortArray) -> Unit
) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Protocol.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(
                Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val size = maxOf(if (min > 0) min else 0, Protocol.SAMPLES * 2 * 2 * 4)
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(size)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            record = rec
            running = true
            rec.startRecording()
            worker = thread(isDaemon = true, name = "was-capture") { loop(rec) }
            true
        } catch (e: Exception) {
            running = false
            false
        }
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val stereo = ShortArray(Protocol.SAMPLES * 2)
        while (running) {
            var got = 0
            while (running && got < stereo.size) {
                val n = rec.read(stereo, got, stereo.size - got, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    running = false
                    break
                }
                got += n
            }
            if (!running) break
            if (outChannels == 2) {
                onFrame(stereo.copyOf())
            } else {
                val mono = ShortArray(Protocol.SAMPLES)
                for (i in mono.indices) {
                    mono[i] = ((stereo[2 * i].toInt() + stereo[2 * i + 1].toInt()) / 2).toShort()
                }
                onFrame(mono)
            }
        }
    }

    fun stop() {
        running = false
        try { worker?.join(400) } catch (e: InterruptedException) { }
        try { record?.stop() } catch (e: Exception) { }
        try { record?.release() } catch (e: Exception) { }
        record = null
    }
}
