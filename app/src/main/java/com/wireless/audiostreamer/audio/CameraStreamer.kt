package com.wireless.audiostreamer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Streams JPEG frames from front or back camera (~5–6 fps) via YUV → JPEG, rotated to portrait. */
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
    private val configured = AtomicBoolean(false)
    private var sensorOrient = 90

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        stop()
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val want = if (useFront) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) == want
            } ?: return false

            val chars = cm.getCameraCharacteristics(id)
            sensorOrient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return false
            val size = chooseSize(yuvSizes)

            val ht = HandlerThread("was-cam").also { it.start() }
            thread = ht
            handler = Handler(ht.looper)

            val latch = CountDownLatch(1)
            val failed = AtomicBoolean(false)

            val ir = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            ir.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (!running) return@setOnImageAvailableListener
                    val now = System.currentTimeMillis()
                    if (now - lastMs < 160) return@setOnImageAvailableListener
                    lastMs = now
                    val jpeg = yuvToJpeg(img, 70) ?: return@setOnImageAvailableListener
                    val rotated = rotatePortrait(jpeg)
                    if (rotated.isNotEmpty()) onJpeg(rotated)
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
                                        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        try {
                                            req.set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                            )
                                        } catch (_: Exception) {
                                            try {
                                                req.set(
                                                    CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                                )
                                            } catch (_: Exception) { }
                                        }
                                        s.setRepeatingRequest(req.build(), null, handler)
                                        configured.set(true)
                                    } catch (_: Exception) {
                                        failed.set(true)
                                    }
                                    latch.countDown()
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    failed.set(true)
                                    latch.countDown()
                                }
                            },
                            handler
                        )
                    } catch (_: Exception) {
                        failed.set(true)
                        latch.countDown()
                    }
                }
                override fun onDisconnected(cam: CameraDevice) {
                    failed.set(true)
                    latch.countDown()
                    try { cam.close() } catch (_: Exception) {}
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    failed.set(true)
                    latch.countDown()
                    try { cam.close() } catch (_: Exception) {}
                }
            }, handler)

            val ok = latch.await(8, TimeUnit.SECONDS) && configured.get() && !failed.get()
            if (!ok) {
                stop()
                return false
            }
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        running = false
        configured.set(false)
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
        return sorted.firstOrNull { it.width in 480..960 && it.height in 360..720 }
            ?: sorted.firstOrNull { it.width * it.height in 200_000..900_000 }
            ?: sorted.getOrElse(sorted.size / 3) { sizes[0] }
    }

    private fun rotatePortrait(jpeg: ByteArray): ByteArray {
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val degrees = if (useFront) {
                (360 - sensorOrient) % 360
            } else {
                sensorOrient
            }
            val m = Matrix()
            if (degrees != 0) m.postRotate(degrees.toFloat())
            if (useFront) m.postScale(-1f, 1f)
            val outBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (outBmp != bmp) bmp.recycle()
            val bos = ByteArrayOutputStream()
            outBmp.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            outBmp.recycle()
            bos.toByteArray()
        } catch (_: Exception) {
            jpeg
        }
    }

    private fun yuvToJpeg(image: Image, quality: Int): ByteArray? {
        return try {
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPix = yPlane.pixelStride
            val uvRowStride = uPlane.rowStride
            val uvPix = uPlane.pixelStride

            val nv21 = ByteArray(width * height * 3 / 2)
            var pos = 0
            if (yPix == 1 && yRowStride == width) {
                yBuf.get(nv21, 0, width * height)
                pos = width * height
            } else {
                for (row in 0 until height) {
                    val yRow = row * yRowStride
                    for (col in 0 until width) {
                        nv21[pos++] = yBuf.get(yRow + col * yPix)
                    }
                }
            }
            val uvHeight = height / 2
            val uvWidth = width / 2
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uIdx = row * uvRowStride + col * uvPix
                    val vIdx = row * vPlane.rowStride + col * vPlane.pixelStride
                    nv21[pos++] = vBuf.get(vIdx)
                    nv21[pos++] = uBuf.get(uIdx)
                }
            }
            val out = ByteArrayOutputStream()
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            if (!yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)) return null
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
