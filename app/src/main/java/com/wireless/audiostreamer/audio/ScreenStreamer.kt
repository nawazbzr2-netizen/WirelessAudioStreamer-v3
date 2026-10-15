package com.wireless.audiostreamer.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Captures the phone screen via MediaProjection + VirtualDisplay,
 * encodes JPEG frames (~8 fps, max width 720) for live cast to receiver.
 */
class ScreenStreamer(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val onJpeg: (ByteArray) -> Unit
) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false
    private var lastMs = 0L
    private var width = 720
    private var height = 1280

    fun start(): Boolean {
        stop()
        return try {
            val wm = ctx.getSystemService(WindowManager::class.java)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val srcW = metrics.widthPixels.coerceAtLeast(1)
            val srcH = metrics.heightPixels.coerceAtLeast(1)
            val density = metrics.densityDpi

            val maxSide = 720
            if (srcW >= srcH) {
                width = maxSide
                height = (srcH * maxSide / srcW).coerceAtLeast(1)
            } else {
                height = maxSide
                width = (srcW * maxSide / srcH).coerceAtLeast(1)
            }
            width = width and 0x7FFFFFFE
            height = height and 0x7FFFFFFE

            val ht = HandlerThread("was-screen").also { it.start() }
            thread = ht
            handler = Handler(ht.looper)

            val ir = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            ir.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (!running) return@setOnImageAvailableListener
                    val now = System.currentTimeMillis()
                    if (now - lastMs < 120) return@setOnImageAvailableListener
                    lastMs = now
                    val jpeg = rgbaToJpeg(img, 65) ?: return@setOnImageAvailableListener
                    if (jpeg.isNotEmpty()) onJpeg(jpeg)
                } catch (_: Exception) {
                } finally {
                    try { img.close() } catch (_: Exception) {}
                }
            }, handler)
            reader = ir

            display = projection.createVirtualDisplay(
                "was-screencast",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ir.surface,
                null,
                handler
            )
            running = display != null
            running
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        running = false
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        thread = null
        handler = null
    }

    private fun rgbaToJpeg(img: Image, quality: Int): ByteArray? {
        return try {
            val plane = img.planes[0]
            val buf: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmp = if (rowPadding == 0) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.copyPixelsFromBuffer(buf)
                }
            } else {
                val tmp = Bitmap.createBitmap(
                    rowStride / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                tmp.copyPixelsFromBuffer(buf)
                Bitmap.createBitmap(tmp, 0, 0, width, height).also {
                    if (tmp != it) tmp.recycle()
                }
            }
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bmp.recycle()
            baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
