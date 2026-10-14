package com.wireless.audiostreamer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.nio.ByteBuffer

/** Streams JPEG frames from front or back camera (~5 fps). */
class CameraStreamer(
    private val ctx: Context,
    private val useFront: Boolean,
    private val onJpeg: (ByteArray) -> Unit
) {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false
    private var lastMs = 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        stop()
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val want = if (useFront) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) == want
            } ?: cm.cameraIdList.firstOrNull() ?: return false

            val chars = cm.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val size = chooseSize(map.getOutputSizes(ImageFormat.JPEG) ?: return false)

            val ht = HandlerThread("was-cam").also { it.start() }
            thread = ht
            handler = Handler(ht.looper)

            val ir = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            ir.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastMs < 180) return@setOnImageAvailableListener
                    lastMs = now
                    val planes = img.planes
                    if (planes.isEmpty()) return@setOnImageAvailableListener
                    val buf: ByteBuffer = planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    if (bytes.isNotEmpty()) onJpeg(bytes)
                } catch (_: Exception) {
                } finally {
                    try { img.close() } catch (_: Exception) {}
                }
            }, handler)
            reader = ir

            running = true
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        cam.createCaptureSession(
                            listOf(ir.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        req.addTarget(ir.surface)
                                        req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        req.set(CaptureRequest.JPEG_QUALITY, 70.toByte())
                                        s.setRepeatingRequest(req.build(), null, handler)
                                    } catch (_: Exception) { stop() }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { stop() }
                            },
                            handler
                        )
                    } catch (_: Exception) { stop() }
                }
                override fun onDisconnected(cam: CameraDevice) { stop() }
                override fun onError(cam: CameraDevice, error: Int) { stop() }
            }, handler)
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        running = false
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        thread = null
        handler = null
    }

    private fun chooseSize(sizes: Array<Size>): Size {
        val sorted = sizes.sortedBy { it.width * it.height }
        return sorted.firstOrNull { it.width in 480..1280 } ?: sorted.getOrElse(sorted.size / 2) { sizes[0] }
    }
}
