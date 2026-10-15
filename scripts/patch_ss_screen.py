#!/usr/bin/env python3
from pathlib import Path
ss = Path("app/src/main/java/com/wireless/audiostreamer/service/StreamingService.kt").read_text()
if "startScreenSend" in ss:
    print("already patched"); raise SystemExit(0)

if "import com.wireless.audiostreamer.audio.ScreenStreamer" not in ss:
    ss = ss.replace(
        "import com.wireless.audiostreamer.audio.SystemAudioCapturer",
        "import com.wireless.audiostreamer.audio.SystemAudioCapturer\nimport com.wireless.audiostreamer.audio.ScreenStreamer",
    )

if "screenStreamer" not in ss:
    ss = ss.replace(
        "private var projCallback: MediaProjection.Callback? = null",
        "private var projCallback: MediaProjection.Callback? = null\n    private var screenStreamer: ScreenStreamer? = null",
    )

old_fgs = """        val type = when {
            streamType == Protocol.STREAM_CAMERA_BACK || streamType == Protocol.STREAM_CAMERA_FRONT ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            role == AppPrefs.ROLE_SENDER && (streamType == Protocol.STREAM_SYSTEM || streamType == Protocol.STREAM_COMBO) ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            role == AppPrefs.ROLE_SENDER ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }"""
new_fgs = """        val type = when {
            streamType == Protocol.STREAM_CAMERA_BACK || streamType == Protocol.STREAM_CAMERA_FRONT ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            role == AppPrefs.ROLE_SENDER && (
                streamType == Protocol.STREAM_SYSTEM ||
                streamType == Protocol.STREAM_COMBO ||
                streamType == Protocol.STREAM_SCREEN
            ) ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            role == AppPrefs.ROLE_SENDER ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }"""
if old_fgs not in ss:
    raise SystemExit("fgs block missing")
ss = ss.replace(old_fgs, new_fgs, 1)

ss = ss.replace(
    "val needsProjection = streamType == Protocol.STREAM_SYSTEM || streamType == Protocol.STREAM_COMBO",
    "val needsProjection = streamType == Protocol.STREAM_SYSTEM || streamType == Protocol.STREAM_COMBO || streamType == Protocol.STREAM_SCREEN",
)

ss = ss.replace(
    """            Protocol.STREAM_CAMERA_FRONT, Protocol.STREAM_CAMERA_BACK -> startCameraSend()
            Protocol.STREAM_STORAGE -> { fileServerThread = FileTransfer.startServer(this) }""",
    """            Protocol.STREAM_CAMERA_FRONT, Protocol.STREAM_CAMERA_BACK -> startCameraSend()
            Protocol.STREAM_SCREEN -> startScreenSend()
            Protocol.STREAM_STORAGE -> { fileServerThread = FileTransfer.startServer(this) }""",
)

ss = ss.replace(
    "if (streamType == Protocol.STREAM_CAMERA_BACK || streamType == Protocol.STREAM_CAMERA_FRONT) startCameraReceive()",
    "if (streamType == Protocol.STREAM_CAMERA_BACK || streamType == Protocol.STREAM_CAMERA_FRONT || streamType == Protocol.STREAM_SCREEN) startCameraReceive()",
)

ss = ss.replace(
    """            streamType == Protocol.STREAM_MIC ||
            streamType == Protocol.STREAM_CAMERA_BACK ||
            streamType == Protocol.STREAM_CAMERA_FRONT ->
                (peerAddr != null && peerIp.isNotBlank()) || mode == MODE_INTERNET""",
    """            streamType == Protocol.STREAM_MIC ||
            streamType == Protocol.STREAM_CAMERA_BACK ||
            streamType == Protocol.STREAM_CAMERA_FRONT ||
            streamType == Protocol.STREAM_SCREEN ->
                (peerAddr != null && peerIp.isNotBlank()) || mode == MODE_INTERNET""",
)

start_screen = """
    private fun startScreenSend() {
        val proj = projection
        if (proj == null) {
            stopEverything(getString(R.string.status_capture_failed), true)
            return
        }
        val streamer = ScreenStreamer(this, proj) { jpeg -> sendCameraFrame(jpeg) }
        screenStreamer = streamer
        if (!streamer.start()) {
            stopEverything(getString(R.string.status_capture_failed), true)
        }
    }
"""
if "startScreenSend" not in ss:
    marker = "    private fun sendCameraFrame(jpeg: ByteArray)"
    if marker in ss:
        ss = ss.replace(marker, start_screen + "\n" + marker, 1)
    else:
        marker2 = "    private fun startCameraReceive()"
        assert marker2 in ss
        ss = ss.replace(marker2, start_screen + "\n" + marker2, 1)

if "screenStreamer?.stop()" not in ss:
    ss = ss.replace(
        "try { micCapturer?.stop() } catch (e: Exception) {}; micCapturer = null",
        "try { screenStreamer?.stop() } catch (e: Exception) {}; screenStreamer = null\n        try { micCapturer?.stop() } catch (e: Exception) {}; micCapturer = null",
    )

Path("app/src/main/java/com/wireless/audiostreamer/service/StreamingService.kt").write_text(ss)
print("StreamingService screen OK", len(ss))
