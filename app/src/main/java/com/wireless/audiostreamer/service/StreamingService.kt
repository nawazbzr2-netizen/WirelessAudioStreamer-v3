package com.wireless.audiostreamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.audio.JitterBuffer
import com.wireless.audiostreamer.audio.LowLatencyPlayer
import com.wireless.audiostreamer.audio.SystemAudioCapturer
import com.wireless.audiostreamer.audio.MicCapturer
import com.wireless.audiostreamer.audio.MicRecorder
import com.wireless.audiostreamer.audio.CameraStreamer
import com.wireless.audiostreamer.network.FileTransfer
import com.wireless.audiostreamer.network.Protocol
import com.wireless.audiostreamer.network.BluetoothTransport
import com.wireless.audiostreamer.network.DiscoveryHelper
import com.wireless.audiostreamer.network.NetUtil
import com.wireless.audiostreamer.network.PacketCipher
import com.wireless.audiostreamer.network.UdpOut
import com.wireless.audiostreamer.ui.MainActivity
import com.wireless.audiostreamer.util.AppPrefs
import com.wireless.audiostreamer.util.LocaleHelper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import kotlin.concurrent.thread

class StreamingService : Service() {

    companion object {
        const val ACTION_START = "com.wireless.audiostreamer.START"
        const val ACTION_STOP = "com.wireless.audiostreamer.STOP"
        const val ACTION_STOP_FEATURE = "com.wireless.audiostreamer.STOP_FEATURE"
        const val ACTION_STATUS = "com.wireless.audiostreamer.STATUS"
        const val EXTRA_ROLE = "role"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PEER_IP = "peer_ip"
        const val EXTRA_BT_ADDRESS = "bt_address"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STREAM = "stream_type"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_ERROR = "error"
        const val EXTRA_TEXT = "text"
        const val MODE_WIFI = "WIFI"
        const val MODE_BT = "BT"
        const val MODE_INTERNET = "INTERNET"
        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1
        private const val PEER_TIMEOUT_MS = 6000L
        private const val AUDIO_TIMEOUT_MS = 3000L
        @Volatile var running = false
        @Volatile var lastConnected = false
        @Volatile var lastError = false
        @Volatile var lastText = ""
        @Volatile var lastFromIp = ""
    }

    private val main = Handler(Looper.getMainLooper())
    private var role = AppPrefs.ROLE_RECEIVER
    private var mode = MODE_WIFI
    private var streamType = Protocol.STREAM_SYSTEM
    private var peerIp = ""
    private var cipher: PacketCipher? = null
    @Volatile private var active = false
    @Volatile private var finished = false
    private var discovery: DiscoveryHelper? = null
    private var udpOut: UdpOut? = null
    @Volatile private var rxSocket: DatagramSocket? = null
    private var bt: BluetoothTransport? = null
    @Volatile private var btConnected = false
    private var jitter: JitterBuffer? = null
    private var player: LowLatencyPlayer? = null
    private var capturer: SystemAudioCapturer? = null
    private var micCapturer: MicCapturer? = null
    private var silentMicRecorder: MicRecorder? = null
    @Volatile private var ctrlSocket: DatagramSocket? = null
    private var cameraStreamer: CameraStreamer? = null
    private var fileServerThread: Thread? = null
    @Volatile private var camSocket: Socket? = null
    private var camServer: ServerSocket? = null
    private val camSendLock = Any()
    private var projection: MediaProjection? = null
    private var projCallback: MediaProjection.Callback? = null
    private var channels = 1
    private var session = 0L
    private var seq = 0
    private var peerAddr: InetAddress? = null
    private var hadPeer = false
    @Volatile private var lastAudioMs = 0L
    @Volatile private var lastFrom = ""
    @Volatile private var curSession = 0L
    @Volatile private var rxChannels = 0
    @Volatile private var authFail = 0
    private var ticker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var shownText = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopEverything(null, false)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP_FEATURE) {
            stopFeatureOnly()
            return START_STICKY
        }
        if (intent.action == ACTION_START) {
            if (active) teardown()
            finished = false
            begin(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (!finished) {
            teardown()
            running = false
            publish(false, getString(R.string.status_idle), false)
        }
        super.onDestroy()
    }

    private fun begin(intent: Intent) {
        role = intent.getStringExtra(EXTRA_ROLE) ?: AppPrefs.role(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_WIFI
        streamType = intent.getStringExtra(EXTRA_STREAM) ?: Protocol.STREAM_SYSTEM
        peerIp = intent.getStringExtra(EXTRA_PEER_IP) ?: ""
        val btAddr = intent.getStringExtra(EXTRA_BT_ADDRESS)
        createChannel()
        val type = when {
            streamType == Protocol.STREAM_CAMERA_BACK || streamType == Protocol.STREAM_CAMERA_FRONT ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            role == AppPrefs.ROLE_SENDER && (streamType == Protocol.STREAM_SYSTEM || streamType == Protocol.STREAM_COMBO) ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            role == AppPrefs.ROLE_SENDER ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(getString(R.string.status_starting)), type)
        } catch (e: Exception) {
            stopEverything(getString(R.string.status_start_failed), true)
            return
        }
        running = true; active = true; hadPeer = false; authFail = 0
        lastAudioMs = 0L; curSession = 0L; rxChannels = 0; shownText = ""
        publish(false, getString(R.string.status_starting), false)
        val pin = AppPrefs.pin(this)
        if (pin.length < 4 || pin.length > 8 || !pin.all { it.isDigit() }) {
            stopEverything(getString(R.string.pin_invalid), true); return
        }
        try {
            cipher = PacketCipher(pin)
            acquireLocks()
            registerNetworkCallback()
            val ok = if (role == AppPrefs.ROLE_SENDER) startSender(intent, btAddr) else startReceiver()
            if (!ok) return
            startTicker()
        } catch (e: Exception) {
            stopEverything(getString(R.string.status_start_failed), true)
        }
    }

    private fun startSender(intent: Intent, btAddr: String?): Boolean {
        channels = AppPrefs.channels(this)
        session = SecureRandom().nextLong()
        seq = 0
        val needsProjection = streamType == Protocol.STREAM_SYSTEM || streamType == Protocol.STREAM_COMBO
        if (needsProjection) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            if (data == null) { stopEverything(getString(R.string.status_capture_failed), true); return false }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() { if (active) stopEverything(getString(R.string.status_projection_stopped), true) }
            }
            proj.registerCallback(cb, main)
            projection = proj; projCallback = cb
        }
        if (mode == MODE_WIFI || mode == MODE_INTERNET) {
            if (mode == MODE_WIFI && !NetUtil.isPrivateIp(peerIp)) {
                stopEverything(getString(R.string.status_start_failed), true); return false
            }
            if (mode == MODE_INTERNET && peerIp.isBlank()) {
                stopEverything(getString(R.string.status_start_failed), true); return false
            }
            peerAddr = InetAddress.getByName(peerIp)
            udpOut = UdpOut(this)
            if (mode == MODE_WIFI) {
                discovery = DiscoveryHelper(this, cipher!!).also {
                    it.start(AppPrefs.deviceName(), AppPrefs.ROLE_SENDER, peerIp)
                }
            }
        } else {
            val adapter = btAdapter()
            if (adapter == null || !adapter.isEnabled || btAddr == null) {
                stopEverything(getString(R.string.status_bt_off), true); return false
            }
            bt = BluetoothTransport(adapter).also { it.startClient(adapter.getRemoteDevice(btAddr), btListener) }
        }
        if (streamType == Protocol.STREAM_SYSTEM || streamType == Protocol.STREAM_COMBO) {
            try { micCapturer?.stop() } catch (_: Exception) {}
            micCapturer = null
        }
        when (streamType) {
            Protocol.STREAM_MIC -> {
                val mic = MicCapturer(channels) { pcm -> onCaptured(pcm) }
                micCapturer = mic
                if (!mic.start()) { stopEverything(getString(R.string.status_capture_failed), true); return false }
            }
            Protocol.STREAM_COMBO -> {
                val proj = projection
                if (proj == null) { stopEverything(getString(R.string.status_capture_failed), true); return false }
                val cap = SystemAudioCapturer(proj, channels) { pcm -> onCaptured(pcm) }
                capturer = cap
                if (!cap.start()) { stopEverything(getString(R.string.status_capture_failed), true); return false }
            }
            Protocol.STREAM_CAMERA_FRONT, Protocol.STREAM_CAMERA_BACK -> startCameraSend()
            Protocol.STREAM_STORAGE -> { fileServerThread = FileTransfer.startServer(this) }
            else -> {
                val proj = projection
                if (proj == null) { stopEverything(getString(R.string.status_capture_failed), true); return false }
                val cap = SystemAudioCapturer(proj, channels) { pcm -> onCaptured(pcm) }
                capturer = cap
                if (!cap.start()) { stopEverything(getString(R.string.status_capture_failed), true); return false }
            }
        }
        if (mode == MODE_WIFI || mode == MODE_INTERNET) {
            thread(isDaemon = true, name = "was-ctrl-rx") { ctrlReceiveLoop() }
        }
        return true
    }

    private fun startReceiver(): Boolean {
        val jb = JitterBuffer(AppPrefs.targetFrames(this))
        jitter = jb
        val pl = LowLatencyPlayer(jb)
        player = pl
        pl.start(1)
        if (mode == MODE_WIFI || mode == MODE_INTERNET) {
            if (mode == MODE_WIFI) {
                discovery = DiscoveryHelper(this, cipher!!).also {
                    it.start(AppPrefs.deviceName(), AppPrefs.ROLE_RECEIVER, "", streamType)
                }
            }
            thread(isDaemon = true, name = "was-udp-rx") { udpReceiveLoop() }
            thread(isDaemon = true, name = "was-ctrl-rx") { ctrlReceiveLoop() }
        } else {
            val adapter = btAdapter()
            if (adapter == null || !adapter.isEnabled) {
                stopEverything(getString(R.string.status_bt_off), true); return false
            }
            bt = BluetoothTransport(adapter).also { it.startServer(btListener) }
        }
        if (streamType == Protocol.STREAM_CAMERA_BACK || streamType == Protocol.STREAM_CAMERA_FRONT) startCameraReceive()
        if (streamType == Protocol.STREAM_STORAGE) fileServerThread = FileTransfer.startServer(this)
        return true
    }

    private fun btAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter
    private val btListener = object : BluetoothTransport.Listener {
        override fun onState(connected: Boolean) { btConnected = connected }
        override fun onFrame(buf: ByteArray, len: Int) { if (role == AppPrefs.ROLE_RECEIVER) handleFrame(buf, len, null) }
    }

    private fun peerReady(): Boolean = when {
        mode == MODE_INTERNET -> true
        mode == MODE_WIFI -> {
            val d = discovery ?: return false
            System.currentTimeMillis() - d.lastSeen(peerIp) < PEER_TIMEOUT_MS
        }
        else -> btConnected
    }

    private fun onCaptured(pcm: ShortArray) {
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
    }

    private fun sendCameraFrame(jpeg: ByteArray) {
        if (!active || (mode != MODE_WIFI && mode != MODE_INTERNET) || peerIp.isBlank()) return
        thread(isDaemon = true, name = "was-cam-tx") {
            synchronized(camSendLock) {
                try {
                    var s = camSocket
                    if (s == null || s.isClosed || !s.isConnected) {
                        try { camSocket?.close() } catch (_: Exception) {}
                        s = Socket()
                        s.tcpNoDelay = true
                        s.connect(InetSocketAddress(peerIp, Protocol.PORT_CAMERA), 5000)
                        camSocket = s
                    }
                    val out = java.io.DataOutputStream(s!!.getOutputStream())
                    out.writeInt(jpeg.size)
                    out.write(jpeg)
                    out.flush()
                } catch (e: Exception) {
                    try { camSocket?.close() } catch (_: Exception) {}
                    camSocket = null
                }
            }
        }
    }

    private fun startCameraReceive() {
        thread(isDaemon = true, name = "was-cam-rx") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(Protocol.PORT_CAMERA))
                camServer = ss
                while (active) {
                    val s = ss.accept()
                    thread(isDaemon = true) {
                        try {
                            val din = java.io.DataInputStream(s.getInputStream())
                            while (active) {
                                val n = din.readInt()
                                if (n <= 0 || n > 2_000_000) break
                                val buf = ByteArray(n)
                                din.readFully(buf)
                                sendBroadcast(Intent("com.wireless.audiostreamer.CAMERA_FRAME").setPackage(packageName).putExtra("jpeg", buf))
                            }
                        } catch (_: Exception) {}
                        finally { try { s.close() } catch (_: Exception) {} }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun udpReceiveLoop() {
        val buf = ByteArray(4096)
        while (active) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null as SocketAddress?)
                s.reuseAddress = true
                s.bind(InetSocketAddress(Protocol.PORT_AUDIO))
                s.soTimeout = 1000
                rxSocket = s
                while (active) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        handleFrame(p.data, p.length, p.address?.hostAddress)
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: Exception) { if (!active) break }
                }
            } catch (_: Exception) {
                try { Thread.sleep(300) } catch (_: Exception) {}
            } finally {
                try { s?.close() } catch (_: Exception) {}
                if (rxSocket === s) rxSocket = null
            }
        }
    }

    private fun handleFrame(buf: ByteArray, len: Int, fromIp: String?) {
        if (mode == MODE_WIFI && !NetUtil.isPrivateIp(fromIp)) return
        val c = cipher ?: return
        val o = c.open(buf, len) ?: run { authFail++; return }
        if (o.type != Protocol.T_AUDIO) return
        val audio = Protocol.parseAudioPlain(o.plain) ?: return
        val jb = jitter ?: return
        if (o.session != curSession) { curSession = o.session; jb.reset() }
        if (audio.channels != rxChannels) { rxChannels = audio.channels; jb.reset(); player?.setChannels(audio.channels) }
        jb.put(o.seq, audio.pcm)
        lastAudioMs = System.currentTimeMillis()
        lastFrom = fromIp ?: "Bluetooth"
        lastFromIp = lastFrom
        if (!hadPeer) {
            hadPeer = true
            publish(true, getString(R.string.status_connected, lastFrom), false)
        }
    }

    private fun ctrlReceiveLoop() {
        while (active) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null as SocketAddress?)
                s.reuseAddress = true
                s.bind(InetSocketAddress(Protocol.PORT_CTRL))
                s.soTimeout = 1000
                ctrlSocket = s
                val buf = ByteArray(512)
                while (active) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val o = cipher?.open(p.data, p.length) ?: continue
                        if (o.type != Protocol.T_CTRL) continue
                        handleCtrl(String(o.plain, Charsets.UTF_8))
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: Exception) { if (!active) break }
                }
            } catch (_: Exception) {
                try { Thread.sleep(300) } catch (_: Exception) {}
            } finally {
                try { s?.close() } catch (_: Exception) {}
                if (ctrlSocket === s) ctrlSocket = null
            }
        }
    }

    private fun handleCtrl(cmd: String) {
        when (cmd) {
            "MIC_REC_START" -> {
                if (silentMicRecorder != null) return
                val r = MicRecorder(this)
                if (r.start()) {
                    silentMicRecorder = r
                    publish(lastConnected, getString(R.string.mic_rec_sender_on), false)
                }
            }
            "MIC_REC_STOP" -> {
                try { silentMicRecorder?.stop() } catch (_: Exception) {}
                silentMicRecorder = null
                publish(lastConnected, getString(R.string.mic_rec_sender_off), false)
            }
        }
    }

    private fun stopFeatureOnly() {
        try { silentMicRecorder?.stop() } catch (e: Exception) {}
        silentMicRecorder = null
        try { micCapturer?.stop() } catch (e: Exception) {}
        micCapturer = null
        try { cameraStreamer?.stop() } catch (e: Exception) {}
        cameraStreamer = null
        try { camSocket?.close() } catch (e: Exception) {}
        camSocket = null
        try { camServer?.close() } catch (e: Exception) {}
        camServer = null
        try { capturer?.stop() } catch (e: Exception) {}
        capturer = null
        player?.stop()
        player = null
        jitter = null
        lastAudioMs = 0L
        shownText = ""
        val msg = getString(R.string.status_idle_connected)
        publish(true, msg, false)
        updateNotification(msg)
    }

    private fun stopEverything(finalText: String?, error: Boolean) {
        if (finished) return
        finished = true; teardown(); running = false
        publish(false, finalText ?: getString(R.string.status_idle), error)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
        stopSelf()
    }

    private fun teardown() {
        active = false
        ticker?.interrupt(); ticker = null
        try { netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (e: Exception) {}
        netCallback = null
        try { micCapturer?.stop() } catch (e: Exception) {}; micCapturer = null
        try { silentMicRecorder?.stop() } catch (e: Exception) {}; silentMicRecorder = null
        try { ctrlSocket?.close() } catch (e: Exception) {}; ctrlSocket = null
        try { cameraStreamer?.stop() } catch (e: Exception) {}; cameraStreamer = null
        try { camSocket?.close() } catch (e: Exception) {}; camSocket = null
        try { camServer?.close() } catch (e: Exception) {}; camServer = null
        try { fileServerThread?.interrupt() } catch (e: Exception) {}; fileServerThread = null
        try { capturer?.stop() } catch (e: Exception) {}; capturer = null
        val proj = projection; val cb = projCallback
        if (proj != null) try { if (cb != null) proj.unregisterCallback(cb); proj.stop() } catch (e: Exception) {}
        projection = null; projCallback = null
        player?.stop(); player = null; jitter = null
        try { rxSocket?.close() } catch (e: Exception) {}; rxSocket = null
        discovery?.stop(); discovery = null
        bt?.stop(); bt = null
        udpOut?.reset(); udpOut = null; cipher = null
        releaseLocks()
    }

    private fun startTicker() {
        ticker = thread(isDaemon = true, name = "was-tick") {
            while (active) {
                try { Thread.sleep(1000) } catch (_: Exception) { break }
            }
        }
    }

    private fun publish(connected: Boolean, text: String, error: Boolean) {
        lastConnected = connected; lastText = text; lastError = error
        if (text == shownText && !error) return
        shownText = text
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running && active)
                .putExtra(EXTRA_CONNECTED, connected)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_ERROR, error)
        )
        updateNotification(text)
    }

    private fun acquireLocks() {
        try { wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "was:wake").apply { acquire() } } catch (e: Exception) {}
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "was:wifi").apply { acquire() }
            multicastLock = wm.createMulticastLock("was:mcast").apply { acquire() }
        } catch (e: Exception) {}
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (e: Exception) {}; wakeLock = null
        try { wifiLock?.release() } catch (e: Exception) {}; wifiLock = null
        try { multicastLock?.release() } catch (e: Exception) {}; multicastLock = null
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { udpOut?.reset() }
                override fun onLost(network: Network) { udpOut?.reset() }
            }
            cm.registerNetworkCallback(req, cb); netCallback = cb
        } catch (e: Exception) {}
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false); nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name)).setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_audio).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.disconnect), stop).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
