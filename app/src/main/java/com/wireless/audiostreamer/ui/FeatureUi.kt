package com.wireless.audiostreamer.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.network.DiscoveryHelper
import com.wireless.audiostreamer.network.PacketCipher
import com.wireless.audiostreamer.network.Protocol
import com.wireless.audiostreamer.network.UdpOut
import com.wireless.audiostreamer.service.StreamingService
import com.wireless.audiostreamer.util.AppPrefs
import java.net.InetAddress

/** UI helpers: active feature color, fullscreen camera, CTRL to sender. */
object FeatureUi {
    private var active: View? = null
    private var fullscreen = false
    private var savedCamH = 0

    fun highlight(activity: Activity, stream: String) {
        clearHighlight(activity)
        val id = when (stream) {
            Protocol.STREAM_MIC -> R.id.btnMicRecv
            Protocol.STREAM_CAMERA_BACK -> R.id.btnCamRecv
            Protocol.STREAM_CAMERA_FRONT -> R.id.btnCamFrontRecv
            Protocol.STREAM_STORAGE -> R.id.btnStorageRecv
            Protocol.STREAM_SYSTEM -> R.id.btnSysRecv
            Protocol.STREAM_COMBO -> R.id.btnSysMicRecv
            else -> return
        }
        val btn = activity.findViewById<View>(id) ?: return
        active = btn
        try {
            btn.setBackgroundColor(0xFF4CAF50.toInt())
            if (btn is Button) btn.setTextColor(0xFFFFFFFF.toInt())
        } catch (_: Exception) {}
    }

    fun clearHighlight(activity: Activity) {
        val btn = active ?: return
        active = null
        try {
            btn.setBackgroundColor(0xFFE8DEF8.toInt())
            if (btn is Button) btn.setTextColor(0xFF4A4458.toInt())
        } catch (_: Exception) {}
    }

    fun toggleFullscreen(activity: Activity, img: ImageView) {
        fullscreen = !fullscreen
        val dens = activity.resources.displayMetrics.density
        if (fullscreen) {
            savedCamH = img.layoutParams.height
            activity.findViewById<View>(R.id.sectionFeatures)?.visibility = View.GONE
            activity.findViewById<View>(R.id.tabLayout)?.visibility = View.GONE
            activity.findViewById<View>(R.id.panelWifi)?.visibility = View.GONE
            activity.findViewById<View>(R.id.panelBluetooth)?.visibility = View.GONE
            activity.findViewById<View>(R.id.btnStartListen)?.visibility = View.GONE
            activity.findViewById<View>(R.id.btnDisconnect)?.visibility = View.GONE
            activity.findViewById<View>(R.id.seekVolume)?.visibility = View.GONE
            img.visibility = View.VISIBLE
            img.layoutParams = img.layoutParams.also {
                it.height = (activity.resources.displayMetrics.heightPixels * 0.75).toInt()
            }
            Toast.makeText(activity, R.string.exit_fullscreen, Toast.LENGTH_SHORT).show()
        } else {
            activity.findViewById<View>(R.id.sectionFeatures)?.visibility = View.VISIBLE
            activity.findViewById<View>(R.id.tabLayout)?.visibility = View.VISIBLE
            activity.findViewById<View>(R.id.seekVolume)?.visibility = View.VISIBLE
            img.layoutParams = img.layoutParams.also {
                it.height = if (savedCamH > 0) savedCamH else (180 * dens).toInt()
            }
        }
        img.requestLayout()
    }

    fun sendCtrl(
        ctx: Context,
        cmd: String,
        pendingPeerIp: String?,
        peers: List<DiscoveryHelper.Peer>
    ): Boolean {
        val host = pendingPeerIp
            ?: peers.firstOrNull { it.role == AppPrefs.ROLE_SENDER }?.ip
            ?: StreamingService.lastFromIp.takeIf { it.isNotBlank() && it != "Bluetooth" && it != "OK" }
            ?: return false
        return try {
            val cipher = PacketCipher(AppPrefs.pin(ctx))
            val frame = cipher.seal(Protocol.T_CTRL, 0L, 0, cmd.toByteArray(Charsets.UTF_8))
            val out = UdpOut(ctx)
            val ok = out.send(frame, frame.size, InetAddress.getByName(host), Protocol.PORT_CTRL)
            out.reset()
            ok
        } catch (_: Exception) {
            false
        }
    }
}
