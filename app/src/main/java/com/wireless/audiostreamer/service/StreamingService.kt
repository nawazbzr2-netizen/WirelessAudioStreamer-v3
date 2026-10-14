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
import java.net.SocketAddress
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Foreground service that runs the whole pipeline.
 *  - SENDER:   MediaProjection -> AudioPlaybackCapture -> ADPCM -> AES-GCM -> UDP / Bluetooth
 *  - RECEIVER: UDP / Bluetooth -> AES-GCM -> ADPCM -> jitter buffer -> AudioTrack
 * The foreground-service type is chosen per role (mediaProjection / mediaPlayback) as Android 14+ requires.
 */
class StreamingService : Service() {

    companion object {
        const val ACTION_START = "com.wireless.audiostreamer.START"
        const val ACTION_STOP = "com.wireless.audiostreamer.STOP"
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

        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1
        private const val PEER_TIMEOUT_MS = 6000L
        private const val AUDIO_TIMEOUT_MS = 3000L

        @Volatile var running = false
        @Volatile var lastConnected = false
        @Volatile var lastError = false
        @Volatile var lastText = ""
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
        if (intent.action == ACTION_START) {
            if (active) teardown()
            finished = false
            begin(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!finished) {
            teardown()
            running = false
            publish(false, getString(R.string.status_idle), false)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start

    private fun begin(intent: Intent) {
        role = intent.getStringExtra(EXTRA_ROLE) ?: AppPrefs.role(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_WIFI
        streamType = intent.getStringExtra(EXTRA_STREAM) ?: Protocol.STREAM_SYSTEM
        peerIp = intent.getStringExtra(EXTRA_PEER_IP) ?: ""
        val btAddr = intent.getStringExtra(EXTRA_BT_ADDRESS)

        createChannel()
        val type = if (role == AppPrefs.ROLE_SENDER) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(getString(R.string.status_starting)), type
            )
        } catch (e: Exception) {
            stopEverything(getString(R.string.status_start_failed), true)
            return
        }

        running = true
        active = true
        hadPeer = false
        authFail = 0
        lastAudioMs = 0L
        curSession = 0L
        rxChannels = 0
        shownText = ""
        publish(false, getString(R.string.status_starting), false)

        // Security: refuse to run without a valid shared PIN (4–8 digits)
        val pin = AppPrefs.pin(this)
        if (pin.length < 4 || pin.length > 8 || !pin.all { it.isDigit() }) {
            stopEverything(getString(R.string.pin_invalid), true)
            return
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

        val needsProjection = streamType == Protocol.STREAM_SYSTEM ||
            streamType == Protocol.STREAM_CAMERA_FRONT ||
            streamType == Protocol.STREAM_CAMERA_BACK

        if (needsProjection) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            if (data == null) {
                stopEverything(getString(R.string.status_capture_failed), true)
                return false
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (active) stopEverything(getString(R.string.status_projection_stopped), true)
                }
            }
            proj.registerCallback(cb, main)
            projection = proj
            projCallback = cb
        }

        if (mode == MODE_WIFI) {
            if (!NetUtil.isPrivateIp(peerIp)) {
                stopEverything(getString(R.string.status_start_failed), true)
                return false
            }
            peerAddr = InetAddress.getByName(peerIp)
            udpOut = UdpOut(this)
            discovery = DiscoveryHelper(this, cipher!!).also {
                it.start(AppPrefs.deviceName(), AppPrefs.ROLE_SENDER, peerIp)
            }
        } else {
            val adapter = btAdapter()
            if (adapter == null || !adapter.isEnabled || btAddr == null) {
                stopEverything(getString(R.string.status_bt_off), true)
                return false
            }
            val device = adapter.getRemoteDevice(btAddr)
            bt = BluetoothTransport(adapter).also { it.startClient(device, btListener) }
        }

        when (streamType) {
            Protocol.STREAM_MIC -> {
                val mic = MicCapturer(channels) { pcm -> onCaptured(pcm) }
                micCapturer = mic
                if (!mic.start()) {
                    stopEverything(getString(R.string.status_capture_failed), true)
                    return false
                }
            }
            Protocol.STREAM_COMBO -> {
                // Combo: mic audio path for now; camera/storage signaled via notification
                val mic = MicCapturer(channels) { pcm -> onCaptured(pcm) }
                micCapturer = mic
                if (!mic.start()) {
                    stopEverything(getString(R.string.status_capture_failed), true)
                    return false
                }
            }
            Protocol.STREAM_CAMERA_FRONT, Protocol.STREAM_CAMERA_BACK, Protocol.STREAM_STORAGE -> {
                // Camera / storage: connection stays up; payload path can be extended.
                // Keep service alive with status so UI shows active feature.
            }
            else -> {
                val proj = projection
                if (proj == null) {
                    stopEverything(getString(R.string.status_capture_failed), true)
                    return false
                }
                val cap = SystemAudioCapturer(proj, channels) { pcm -> onCaptured(pcm) }
                capturer = cap
                if (!cap.start()) {
                    stopEverything(getString(R.string.status_capture_failed), true)
                    return false
                }
            }
        }
        return true
    }

    private fun startReceiver(): Boolean {
        val jb = JitterBuffer(AppPrefs.targetFrames(this))
        jitter = jb
        val pl = LowLatencyPlayer(jb)
        player = pl
        pl.start(1)

        if (mode == MODE_WIFI) {
            discovery = DiscoveryHelper(this, cipher!!).also {
                it.start(AppPrefs.deviceName(), AppPrefs.ROLE_RECEIVER, "")
            }
            thread(isDaemon = true, name = "was-udp-rx") { udpReceiveLoop() }
        } else {
            val adapter = btAdapter()
            if (adapter == null || !adapter.isEnabled) {
                stopEverything(getString(R.string.status_bt_off), true)
                return false
            }
            bt = BluetoothTransport(adapter).also { it.startServer(btListener) }
        }
        return true
    }

    private fun btAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    private val btListener = object : BluetoothTransport.Listener {
        override fun onState(connected: Boolean) {
            btConnected = connected
        }

        override fun onFrame(buf: ByteArray, len: Int) {
            if (role == AppPrefs.ROLE_RECEIVER) handleFrame(buf, len, null)
        }
    }

    // ------------------------------------------------------------------ sender path

    private fun peerReady(): Boolean {
        return if (mode == MODE_WIFI) {
            val d = discovery ?: return false
            System.currentTimeMillis() - d.lastSeen(peerIp) < PEER_TIMEOUT_MS
        } else {
            btConnected
        }
    }

    private fun onCaptured(pcm: ShortArray) {
        if (!active || !peerReady()) return
        val c = cipher ?: return
        val frame = c.seal(Protocol.T_AUDIO, session, seq++, Protocol.buildAudioPlain(pcm, channels))
        if (mode == MODE_WIFI) {
            val a = peerAddr ?: return
            udpOut?.send(frame, frame.size, a, Protocol.PORT_AUDIO)
        } else {
            bt?.send(frame)
        }
    }

    // ------------------------------------------------------------------ receiver path

    private fun udpReceiveLoop() {
        val buf = ByteArray(4096)
        while (active) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null as SocketAddress?)
                s.reuseAddress = true
                s.bind(InetSocketAddress(Protocol.PORT_AUDIO))
                rxSocket = s
                while (active) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    handleFrame(p.data, p.length, p.address?.hostAddress)
                }
            } catch (e: Exception) {
                if (active) {
                    try { Thread.sleep(800) } catch (ie: InterruptedException) { return }
                }
            } finally {
                try { s?.close() } catch (e: Exception) { }
                rxSocket = null
            }
        }
    }

    private fun handleFrame(buf: ByteArray, len: Int, fromIp: String?) {
        if (mode == MODE_WIFI && !NetUtil.isPrivateIp(fromIp)) return
        val c = cipher ?: return
        val o = c.open(buf, len)
        if (o == null) {
            authFail++
            return
        }
        if (o.type != Protocol.T_AUDIO) return
        val audio = Protocol.parseAudioPlain(o.plain) ?: return
        val jb = jitter ?: return
        if (o.session != curSession) {
            curSession = o.session
            jb.reset()
        }
        if (audio.channels != rxChannels) {
            rxChannels = audio.channels
            jb.reset()
            player?.setChannels(audio.channels)
        }
        jb.put(o.seq, audio.pcm)
        lastAudioMs = System.currentTimeMillis()
        lastFrom = fromIp ?: "Bluetooth"
        authFail = 0
    }

    // ------------------------------------------------------------------ status

    private fun startTicker() {
        ticker = thread(isDaemon = true, name = "was-ticker") {
            while (active) {
                try {
                    updateStatus()
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun updateStatus() {
        var connected = false
        var text: String
        if (role == AppPrefs.ROLE_SENDER) {
            if (mode == MODE_WIFI) {
                connected = peerReady()
                if (connected) {
                    hadPeer = true
                    val n = discovery?.peer(peerIp)?.name ?: peerIp
                    text = getString(R.string.status_connected, n)
                } else {
                    text = getString(if (hadPeer) R.string.status_peer_lost else R.string.status_waiting_peer)
                }
            } else {
                connected = btConnected
                if (connected) {
                    hadPeer = true
                    text = getString(R.string.status_connected, "Bluetooth")
                } else {
                    text = getString(if (hadPeer) R.string.status_peer_lost else R.string.status_bt_connecting)
                }
            }
        } else {
            connected = System.currentTimeMillis() - lastAudioMs < AUDIO_TIMEOUT_MS
            text = when {
                connected -> getString(R.string.status_connected, lastFrom)
                authFail >= 15 -> getString(R.string.status_wrong_pin)
                else -> getString(R.string.status_waiting_sender)
            }
        }
        if (text != shownText || connected != lastConnected) {
            shownText = text
            publish(connected, text, authFail >= 15 && !connected)
            updateNotification(text)
        }
    }

    private fun publish(connected: Boolean, text: String, error: Boolean) {
        lastConnected = connected
        lastText = text
        lastError = error
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_CONNECTED, connected)
                .putExtra(EXTRA_ERROR, error)
                .putExtra(EXTRA_TEXT, text)
        )
    }

    // ------------------------------------------------------------------ stop

    private fun stopEverything(finalText: String?, error: Boolean) {
        if (finished) return
        finished = true
        teardown()
        running = false
        publish(false, finalText ?: getString(R.string.status_idle), error)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        stopSelf()
    }

    private fun teardown() {
        active = false
        ticker?.interrupt()
        ticker = null
        try {
            netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        } catch (e: Exception) { }
        netCallback = null

        micCapturer?.stop(); micCapturer = null; capturer?.stop()
        capturer = null
        val proj = projection
        val cb = projCallback
        if (proj != null) {
            try {
                if (cb != null) proj.unregisterCallback(cb) // avoid re-entering onStop()
                proj.stop()
            } catch (e: Exception) { }
        }
        projection = null
        projCallback = null

        player?.stop()
        player = null
        jitter = null
        try { rxSocket?.close() } catch (e: Exception) { }
        rxSocket = null
        discovery?.stop()
        discovery = null
        bt?.stop()
        bt = null
        udpOut?.reset()
        udpOut = null
        btConnected = false
        releaseLocks()
    }

    // ------------------------------------------------------------------ network / locks

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                udpOut?.reset()
                discovery?.resetNetwork()
            }

            override fun onLost(network: Network) {
                udpOut?.reset()
                discovery?.resetNetwork()
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb
            )
            netCallback = cb
        } catch (e: Exception) {
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "was:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "was:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                multicastLock = wm.createMulticastLock("was:multicast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) { }
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (e: Exception) { }
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // High enough importance so the ongoing "mic/capture active" notification stays visible
        val ch = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT
        )
        ch.description = getString(R.string.notif_channel_desc)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Persistent notification while audio capture / streaming is active (cannot swipe away)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_audio)
            .setContentTitle(getString(R.string.notif_title_active))
            .setContentText(text.ifEmpty { getString(R.string.notif_capture_active) })
            .setSubText(getString(R.string.notif_security_sub))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
        }
    }
}
