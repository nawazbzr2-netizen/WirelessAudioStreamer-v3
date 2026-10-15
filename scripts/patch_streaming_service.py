#!/usr/bin/env python3
from pathlib import Path
t = Path("app/src/main/java/com/wireless/audiostreamer/service/StreamingService.kt").read_text()
old = """    private fun onCaptured(pcm: ShortArray) {
        if (!active || !peerReady()) return
        val c = cipher ?: return
        lastAudioMs = System.currentTimeMillis()
        val frame = c.seal(Protocol.T_AUDIO, session, seq++, Protocol.buildAudioPlain(pcm, channels))
        if (mode == MODE_WIFI || mode == MODE_INTERNET) {
            val a = peerAddr ?: return
            udpOut?.send(frame, frame.size, a, Protocol.PORT_AUDIO)
        } else bt?.send(frame)
    }

    private fun startCameraSend() {
        val useFront = streamType == Protocol.STREAM_CAMERA_FRONT
        val cam = CameraStreamer(this, useFront) { jpeg -> sendCameraFrame(jpeg) }
        cameraStreamer = cam
        if (!cam.start()) stopEverything(getString(R.string.status_capture_failed), true)
    }"""
new = """    private fun onCaptured(pcm: ShortArray) {
        if (!active) return
        val canSend = when {
            mode == MODE_BT -> btConnected
            streamType == Protocol.STREAM_MIC ||
            streamType == Protocol.STREAM_CAMERA_BACK ||
            streamType == Protocol.STREAM_CAMERA_FRONT ->
                (peerAddr != null && peerIp.isNotBlank()) || mode == MODE_INTERNET
            else -> peerReady()
        }
        if (!canSend) return
        val c = cipher ?: return
        lastAudioMs = System.currentTimeMillis()
        val frame = c.seal(Protocol.T_AUDIO, session, seq++, Protocol.buildAudioPlain(pcm, channels))
        if (mode == MODE_WIFI || mode == MODE_INTERNET) {
            val a = peerAddr ?: return
            udpOut?.send(frame, frame.size, a, Protocol.PORT_AUDIO)
        } else bt?.send(frame)
    }

    private fun startCameraSend() {
        val useFront = streamType == Protocol.STREAM_CAMERA_FRONT
        val cam = CameraStreamer(this, useFront) { jpeg -> sendCameraFrame(jpeg) }
        cameraStreamer = cam
        if (!cam.start()) {
            stopEverything(getString(R.string.status_capture_failed), true)
            return
        }
        try { micCapturer?.stop() } catch (_: Exception) {}
        val mic = MicCapturer(channels) { pcm -> onCaptured(pcm) }
        micCapturer = mic
        mic.start()
    }"""
if old not in t:
    raise SystemExit("ss block1 not found")
t = t.replace(old, new, 1)
old2 = """        jb.put(o.seq, audio.pcm)
        lastAudioMs = System.currentTimeMillis()
        lastFrom = fromIp ?: "Bluetooth"
        lastFromIp = lastFrom"""
new2 = """        jb.put(o.seq, audio.pcm)
        lastAudioMs = System.currentTimeMillis()
        lastFrom = fromIp ?: "Bluetooth"
        lastFromIp = lastFrom
        try {
            sendBroadcast(
                Intent("com.wireless.audiostreamer.AUDIO_PCM")
                    .setPackage(packageName)
                    .putExtra("pcm", audio.pcm)
                    .putExtra("ch", audio.channels)
            )
        } catch (_: Exception) {}"""
if old2 not in t:
    raise SystemExit("ss block2 not found")
t = t.replace(old2, new2, 1)
Path("app/src/main/java/com/wireless/audiostreamer/service/StreamingService.kt").write_text(t)
print("StreamingService patched", len(t))
