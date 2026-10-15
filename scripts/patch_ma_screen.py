#!/usr/bin/env python3
from pathlib import Path
ma = Path("app/src/main/java/com/wireless/audiostreamer/ui/MainActivity.kt").read_text()
if "btnScreenRecv" in ma and "STREAM_SCREEN" in ma:
    print("already patched"); raise SystemExit(0)

ma = ma.replace(
    "if (stream == Protocol.STREAM_SYSTEM || stream == Protocol.STREAM_COMBO) {",
    "if (stream == Protocol.STREAM_SYSTEM || stream == Protocol.STREAM_COMBO || stream == Protocol.STREAM_SCREEN) {",
)

old = """        findViewById<Button>(R.id.btnVoiceRecord).setOnClickListener { startVoiceRecord() }
        findViewById<Button>(R.id.btnVoiceRecordStop).setOnClickListener { stopVoiceRecord() }

        btnSenderReady.setOnClickListener"""
new = """        findViewById<Button>(R.id.btnVoiceRecord).setOnClickListener { startVoiceRecord() }
        findViewById<Button>(R.id.btnVoiceRecordStop).setOnClickListener { stopVoiceRecord() }

        findViewById<Button>(R.id.btnScreenRecv).setOnClickListener {
            openFeature(Protocol.STREAM_SCREEN)
            openCameraFullscreen()
        }
        findViewById<Button>(R.id.btnScreenStop).setOnClickListener {
            closeCameraFullscreen()
            stopFeature()
        }

        btnSenderReady.setOnClickListener"""
if old not in ma:
    raise SystemExit("button block missing")
ma = ma.replace(old, new, 1)

ma = ma.replace(
    """    private fun streamLabel(stream: String): String = when (stream) {
        Protocol.STREAM_MIC -> getString(R.string.feat_mic)
        Protocol.STREAM_CAMERA_FRONT -> getString(R.string.feat_cam_front)
        Protocol.STREAM_CAMERA_BACK -> getString(R.string.feat_camera)
        Protocol.STREAM_STORAGE -> getString(R.string.feat_storage)
        Protocol.STREAM_COMBO -> getString(R.string.feat_sys_mic)
        else -> getString(R.string.feat_system)
    }""",
    """    private fun streamLabel(stream: String): String = when (stream) {
        Protocol.STREAM_MIC -> getString(R.string.feat_mic)
        Protocol.STREAM_CAMERA_FRONT -> getString(R.string.feat_cam_front)
        Protocol.STREAM_CAMERA_BACK -> getString(R.string.feat_camera)
        Protocol.STREAM_STORAGE -> getString(R.string.feat_storage)
        Protocol.STREAM_COMBO -> getString(R.string.feat_sys_mic)
        Protocol.STREAM_SCREEN -> getString(R.string.feat_screen)
        else -> getString(R.string.feat_system)
    }""",
)

Path("app/src/main/java/com/wireless/audiostreamer/ui/MainActivity.kt").write_text(ma)
print("MainActivity screen OK", len(ma))
