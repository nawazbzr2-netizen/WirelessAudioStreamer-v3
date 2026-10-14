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
 * Captures internal playback only (media / game).
 * Explicitly excludes voice-communication and assistant usages so the
 * sender microphone is never mixed into "System sound".
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
                .excludeUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .excludeUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .excludeUsage(AudioAttributes.USAGE_ASSISTANT)
                .excludeUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .excludeUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .excludeUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .excludeUsage(AudioAttributes.USAGE_NOTIFICATION)
                .excludeUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .excludeUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST)
                .excludeUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .excludeUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED)
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
