package com.wireless.audiostreamer.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.wireless.audiostreamer.network.Protocol
import kotlin.concurrent.thread

/**
 * Captures microphone audio (not system playback). Same frame size as SystemAudioCapturer
 * so the existing ADPCM / jitter path can be reused.
 */
class MicCapturer(
    private val outChannels: Int,
    private val onFrame: (ShortArray) -> Unit
) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            val chMask = if (outChannels == 2)
                AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Protocol.SAMPLE_RATE)
                .setChannelMask(chMask)
                .build()
            val min = AudioRecord.getMinBufferSize(
                Protocol.SAMPLE_RATE, chMask, AudioFormat.ENCODING_PCM_16BIT
            )
            val size = maxOf(if (min > 0) min else 0, Protocol.SAMPLES * outChannels * 2 * 4)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                Protocol.SAMPLE_RATE,
                chMask,
                AudioFormat.ENCODING_PCM_16BIT,
                size
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            record = rec
            running = true
            rec.startRecording()
            worker = thread(isDaemon = true, name = "was-mic") { loop(rec) }
            true
        } catch (e: Exception) {
            running = false
            false
        }
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val need = Protocol.SAMPLES * outChannels
        val buf = ShortArray(need)
        while (running) {
            var got = 0
            while (got < need && running) {
                val n = rec.read(buf, got, need - got)
                if (n <= 0) break
                got += n
            }
            if (got == need && running) onFrame(buf.copyOf())
        }
    }

    fun stop() {
        running = false
        try { worker?.join(500) } catch (_: Exception) { }
        worker = null
        try { record?.stop() } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }
        record = null
    }
}
