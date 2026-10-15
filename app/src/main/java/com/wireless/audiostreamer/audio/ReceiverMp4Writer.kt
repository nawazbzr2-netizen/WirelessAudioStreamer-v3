package com.wireless.audiostreamer.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Process
import com.wireless.audiostreamer.network.Protocol
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Records live JPEG frames + PCM into MP4 (H.264 480p + AAC) on Receiver. */
class ReceiverMp4Writer(private val ctx: Context) {
    companion object {
        const val OUT_W = 640
        const val OUT_H = 480
        const val VIDEO_BITRATE = 1_200_000
        const val FRAME_RATE = 6
        const val I_FRAME = 1
    }

    @Volatile var isRecording = false
        private set

    private var muxer: MediaMuxer? = null
    private var path: String? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private var videoPtsUs = 0L
    private var audioPtsUs = 0L
    private var worker: Thread? = null
    private val jpegQ = LinkedBlockingQueue<ByteArray>(8)
    private val pcmQ = LinkedBlockingQueue<ShortArray>(32)
    private var frames = 0

    fun start(): String? {
        stop()
        return try {
            val dir = File(ctx.getExternalFilesDir(null), "recordings").also { it.mkdirs() }
            val f = File(dir, "cam_${System.currentTimeMillis()}.mp4")
            path = f.absolutePath
            muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, OUT_W, OUT_H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME)
            }
            val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vc.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            vc.start()
            videoCodec = vc
            val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Protocol.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ac.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            audioCodec = ac
            videoPtsUs = 0; audioPtsUs = 0; frames = 0
            muxerStarted = false; videoTrack = -1; audioTrack = -1
            running.set(true); isRecording = true
            worker = thread(isDaemon = true, name = "was-mp4") { loop() }
            path
        } catch (_: Exception) { stop(); null }
    }

    fun writeJpeg(jpeg: ByteArray) {
        if (!isRecording) return
        if (jpegQ.remainingCapacity() == 0) jpegQ.poll()
        jpegQ.offer(jpeg.copyOf())
    }

    fun writePcm(pcm: ShortArray, channels: Int) {
        if (!isRecording) return
        val mono = if (channels <= 1) pcm.copyOf() else {
            val n = pcm.size / channels
            ShortArray(n) { i ->
                var s = 0
                for (c in 0 until channels) s += pcm[i * channels + c].toInt()
                (s / channels).toShort()
            }
        }
        if (pcmQ.remainingCapacity() == 0) pcmQ.poll()
        pcmQ.offer(mono)
    }

    fun stop(): String? {
        isRecording = false
        running.set(false)
        try { worker?.join(1500) } catch (_: Exception) {}
        worker = null
        jpegQ.clear(); pcmQ.clear()
        synchronized(lock) {
            try { videoCodec?.stop() } catch (_: Exception) {}
            try { videoCodec?.release() } catch (_: Exception) {}
            videoCodec = null
            try { audioCodec?.stop() } catch (_: Exception) {}
            try { audioCodec?.release() } catch (_: Exception) {}
            audioCodec = null
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null; muxerStarted = false
        }
        val p = path; path = null
        return if (frames > 0) p else { try { p?.let { File(it).delete() } } catch (_: Exception) {}; null }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val info = MediaCodec.BufferInfo()
        while (running.get() || jpegQ.isNotEmpty() || pcmQ.isNotEmpty()) {
            try {
                val jpeg = jpegQ.poll()
                if (jpeg != null) encodeVideoFrame(jpeg, info)
                val pcm = pcmQ.poll()
                if (pcm != null) encodeAudioFrame(pcm, info)
                if (jpeg == null && pcm == null) Thread.sleep(15)
            } catch (_: Exception) {}
        }
        try { drainVideo(info, true); drainAudio(info, true) } catch (_: Exception) {}
    }

    private fun encodeVideoFrame(jpeg: ByteArray, info: MediaCodec.BufferInfo) {
        val vc = videoCodec ?: return
        val nv12 = jpegToNv12(jpeg) ?: return
        val ix = vc.dequeueInputBuffer(50_000)
        if (ix >= 0) {
            val inBuf = vc.getInputBuffer(ix) ?: return
            inBuf.clear(); inBuf.put(nv12)
            vc.queueInputBuffer(ix, 0, nv12.size, videoPtsUs, 0)
            videoPtsUs += 1_000_000L / FRAME_RATE
            frames++
        }
        drainVideo(info, false)
    }

    private fun encodeAudioFrame(pcm: ShortArray, info: MediaCodec.BufferInfo) {
        val ac = audioCodec ?: return
        val ix = ac.dequeueInputBuffer(20_000)
        if (ix >= 0) {
            val inBuf = ac.getInputBuffer(ix) ?: return
            inBuf.clear()
            val bytes = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val v = pcm[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            inBuf.put(bytes)
            val dur = pcm.size * 1_000_000L / Protocol.SAMPLE_RATE
            ac.queueInputBuffer(ix, 0, bytes.size, audioPtsUs, 0)
            audioPtsUs += dur
        }
        drainAudio(info, false)
    }

    private fun drainVideo(info: MediaCodec.BufferInfo, end: Boolean) {
        val vc = videoCodec ?: return
        while (true) {
            val ox = vc.dequeueOutputBuffer(info, if (end) 50_000 else 0)
            when {
                ox == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(lock) {
                        if (videoTrack < 0) { videoTrack = muxer!!.addTrack(vc.outputFormat); maybeStartMuxer() }
                    }
                }
                ox >= 0 -> {
                    val out = vc.getOutputBuffer(ox)
                    if (out != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        synchronized(lock) {
                            try {
                                out.position(info.offset); out.limit(info.offset + info.size)
                                muxer?.writeSampleData(videoTrack, out, info)
                            } catch (_: Exception) {}
                        }
                    }
                    vc.releaseOutputBuffer(ox, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> return
            }
        }
    }

    private fun drainAudio(info: MediaCodec.BufferInfo, end: Boolean) {
        val ac = audioCodec ?: return
        while (true) {
            val ox = ac.dequeueOutputBuffer(info, if (end) 50_000 else 0)
            when {
                ox == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(lock) {
                        if (audioTrack < 0) { audioTrack = muxer!!.addTrack(ac.outputFormat); maybeStartMuxer() }
                    }
                }
                ox >= 0 -> {
                    val out = ac.getOutputBuffer(ox)
                    if (out != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        synchronized(lock) {
                            try {
                                out.position(info.offset); out.limit(info.offset + info.size)
                                muxer?.writeSampleData(audioTrack, out, info)
                            } catch (_: Exception) {}
                        }
                    }
                    ac.releaseOutputBuffer(ox, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> return
            }
        }
    }

    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
            muxer?.start(); muxerStarted = true
        }
    }

    private fun jpegToNv12(jpeg: ByteArray): ByteArray? {
        return try {
            val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val scaled = Bitmap.createScaledBitmap(src, OUT_W, OUT_H, true)
            if (scaled != src) src.recycle()
            val w = OUT_W; val h = OUT_H
            val argb = IntArray(w * h)
            scaled.getPixels(argb, 0, w, 0, 0, w, h)
            scaled.recycle()
            val ySize = w * h
            val nv12 = ByteArray(ySize + ySize / 2)
            var yIndex = 0; var uvIndex = ySize
            for (j in 0 until h) {
                for (i in 0 until w) {
                    val c = argb[j * w + i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    nv12[yIndex++] = y.coerceIn(0, 255).toByte()
                    if (j % 2 == 0 && i % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        nv12[uvIndex++] = u.coerceIn(0, 255).toByte()
                        nv12[uvIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
            nv12
        } catch (_: Exception) { null }
    }
}
