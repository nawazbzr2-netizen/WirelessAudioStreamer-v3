#!/usr/bin/env python3
from pathlib import Path
p = Path("app/src/main/java/com/wireless/audiostreamer/service/StreamingService.kt")
ss = p.read_text()
if "private fun startScreenSend()" in ss:
    print("startScreenSend already present")
    raise SystemExit(0)
fn = """
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
marker = "    private fun sendCameraFrame(jpeg: ByteArray)"
if marker not in ss:
    raise SystemExit("sendCameraFrame marker missing")
ss = ss.replace(marker, fn + marker, 1)
p.write_text(ss)
print("inserted startScreenSend OK", len(ss))
