#!/usr/bin/env python3
from pathlib import Path
p = Path("app/src/main/java/com/wireless/audiostreamer/ui/MainActivity.kt")
ma = p.read_text()
ma = ma.replace("import com.wireless.audiostreamer.audio.ReceiverVideoWriter", "import com.wireless.audiostreamer.audio.ReceiverMp4Writer")
ma = ma.replace(
    "private var videoWriter: ReceiverVideoWriter? = null",
    """private var videoWriter: ReceiverMp4Writer? = null
    private val audioPcmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pcm = intent?.getShortArrayExtra("pcm") ?: return
            val ch = intent.getIntExtra("ch", 1)
            videoWriter?.writePcm(pcm, ch)
        }
    }""",
)
if "import android.content.BroadcastReceiver" not in ma:
    ma = ma.replace("import android.content.Context", "import android.content.BroadcastReceiver\nimport android.content.Context")
ma = ma.replace(
    'ContextCompat.registerReceiver(this, cameraReceiver, IntentFilter("com.wireless.audiostreamer.CAMERA_FRAME"), ContextCompat.RECEIVER_NOT_EXPORTED)',
    """ContextCompat.registerReceiver(this, cameraReceiver, IntentFilter("com.wireless.audiostreamer.CAMERA_FRAME"), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, audioPcmReceiver, IntentFilter("com.wireless.audiostreamer.AUDIO_PCM"), ContextCompat.RECEIVER_NOT_EXPORTED)""",
)
ma = ma.replace(
    "try { unregisterReceiver(cameraReceiver) } catch (_: Exception) {}",
    """try { unregisterReceiver(cameraReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(audioPcmReceiver) } catch (_: Exception) {}""",
)
old_btns = """        findViewById<Button>(R.id.btnCamRecv).setOnClickListener {
            openFeature(Protocol.STREAM_CAMERA_BACK)
            openCameraFullscreen()
        }
        findViewById<Button>(R.id.btnCamStop).setOnClickListener { closeCameraFullscreen() }
        findViewById<Button>(R.id.btnCamPhoto).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.btnCamVideo).setOnClickListener { toggleVideoRecord() }
        findViewById<Button>(R.id.btnCamVideoStop).setOnClickListener { stopVideoRecord() }

        findViewById<Button>(R.id.btnCamFrontRecv).setOnClickListener {
            openFeature(Protocol.STREAM_CAMERA_FRONT)
            openCameraFullscreen()
        }
        findViewById<Button>(R.id.btnCamFrontStop).setOnClickListener { closeCameraFullscreen() }"""
new_btns = """        findViewById<Button>(R.id.btnCamRecv).setOnClickListener {
            openFeature(Protocol.STREAM_CAMERA_BACK)
            openCameraFullscreen()
        }
        findViewById<Button>(R.id.btnCamStop).setOnClickListener { closeCameraFullscreen() }
        findViewById<Button>(R.id.btnCamVideoStop).setOnClickListener { toggleVideoRecord() }

        findViewById<Button>(R.id.btnCamFrontRecv).setOnClickListener {
            openFeature(Protocol.STREAM_CAMERA_FRONT)
            openCameraFullscreen()
        }
        findViewById<Button>(R.id.btnCamFrontStop).setOnClickListener { closeCameraFullscreen() }
        findViewById<Button>(R.id.btnCamFrontRecord).setOnClickListener { toggleVideoRecord() }"""
if old_btns not in ma:
    raise SystemExit("ma buttons missing")
ma = ma.replace(old_btns, new_btns, 1)
ma = ma.replace("ReceiverVideoWriter(this)", "ReceiverMp4Writer(this)")
ma = ma.replace(
    "btnCamRec?.text = getString(R.string.btn_stop_video)",
    """btnCamRec?.text = getString(R.string.btn_stop_recording)
            try { findViewById<Button>(R.id.btnCamVideoStop).text = getString(R.string.btn_stop_recording) } catch (_: Exception) {}
            try { findViewById<Button>(R.id.btnCamFrontRecord).text = getString(R.string.btn_stop_recording) } catch (_: Exception) {}""",
)
ma = ma.replace(
    """    private fun openCameraFullscreen() {
        cameraOverlay?.visibility = View.VISIBLE
        imgCamera.visibility = View.VISIBLE
        btnCamRec?.text = getString(R.string.btn_video)
    }""",
    """    private fun openCameraFullscreen() {
        cameraOverlay?.visibility = View.VISIBLE
        imgCamera.visibility = View.VISIBLE
        btnCamRec?.text = getString(R.string.btn_start_recording)
        try { findViewById<Button>(R.id.btnCamVideoStop).text = getString(R.string.btn_start_recording) } catch (_: Exception) {}
        try { findViewById<Button>(R.id.btnCamFrontRecord).text = getString(R.string.btn_start_recording) } catch (_: Exception) {}
    }""",
)
ma = ma.replace(
    "btnCamRec?.text = getString(R.string.btn_video)",
    """btnCamRec?.text = getString(R.string.btn_start_recording)
        try { findViewById<Button>(R.id.btnCamVideoStop).text = getString(R.string.btn_start_recording) } catch (_: Exception) {}
        try { findViewById<Button>(R.id.btnCamFrontRecord).text = getString(R.string.btn_start_recording) } catch (_: Exception) {}""",
)
p.write_text(ma)
print("MainActivity patched", len(ma))
