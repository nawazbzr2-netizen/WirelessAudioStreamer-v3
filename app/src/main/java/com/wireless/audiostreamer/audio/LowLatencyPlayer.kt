package com.wireless.audiostreamer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.wireless.audiostreamer.network.Protocol
import kotlin.concurrent.thread

class LowLatencyPlayer(private val jitter: JitterBuffer) {
    @Volatile private var running = false
    @Volatile private var wantChannels = 1
    private var track: AudioTrack? = null
    private var trackChannels = 0
    private var worker: Thread? = null

    fun start(channels: Int) {
        wantChannels = channels
        running = true
        worker = thread(isDaemon = true, name = "was-player") { loop() }
    }

    fun setChannels(ch: Int) {
        wantChannels = ch
    }

    fun stop() {
        running = false
        worker?.interrupt()
        try { worker?.join(400) } catch (e: InterruptedException) { }
        releaseTrack()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            try {
                ensureTrack()
                val t = track
                if (t == null) {
                    Thread.sleep(50)
                    continue
                }
                val f = jitter.poll()
                if (f == null) {
                    if (!jitter.isPrimed()) {
                        Thread.sleep(5)
                        continue
                    }
                    val silence = ShortArray(Protocol.SAMPLES * trackChannels)
                    t.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    t.write(f, 0, f.size, AudioTrack.WRITE_BLOCKING)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                try { Thread.sleep(50) } catch (ie: InterruptedException) { break }
            }
        }
    }

    private fun ensureTrack() {
        val ch = wantChannels
        if (track != null && trackChannels == ch) return
        releaseTrack()
        val mask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(Protocol.SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(if (min > 0) min else 0, Protocol.SAMPLES * ch * 2 * 3)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Protocol.SAMPLE_RATE)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        t.play()
        track = t
        trackChannels = ch
    }

    private fun releaseTrack() {
        try { track?.stop() } catch (e: Exception) { }
        try { track?.release() } catch (e: Exception) { }
        track = null
        trackChannels = 0
    }
}
