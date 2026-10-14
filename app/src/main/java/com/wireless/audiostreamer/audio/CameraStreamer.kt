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

/** Opens the back camera and emits JPEG frames (~5 fps) for network streaming. */
class CameraStreamer(
    private val ctx: Context,
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
            val id = cm.cameraIdList.firstOrNull { cid ->
                val facing = cm.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return false

            val chars = cm.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val size = chooseSize(map.getOutputSizes(ImageFormat.JPEG))

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
                    val buf: ByteBuffer = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    if (running) onJpeg(bytes)
                } finally {
                    img.close()
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
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    req.addTarget(ir.surface)
                                    req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    s.setRepeatingRequest(req.build(), null, handler)
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { stop() }
                            },
                            handler
                        )
                    } catch (e: Exception) { stop() }
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

    private fun chooseSize(sizes: Array<Size>): Size {
        return sizes
            .filter { it.width * it.height <= 640 * 480 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(640, 480)
    }

    fun stop() {
        running = false
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { reader?.close() } catch (_: Exception) { }
        reader = null
        try { thread?.quitSafely() } catch (_: Exception) { }
        thread = null
        handler = null
    }
}
