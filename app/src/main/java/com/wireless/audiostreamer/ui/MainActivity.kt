package com.wireless.audiostreamer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.audio.MicRecorder
import com.wireless.audiostreamer.audio.ReceiverVideoWriter
import com.wireless.audiostreamer.network.DiscoveryHelper
import com.wireless.audiostreamer.network.FileTransfer
import com.wireless.audiostreamer.network.NetUtil
import com.wireless.audiostreamer.network.PacketCipher
import com.wireless.audiostreamer.network.Protocol
import com.wireless.audiostreamer.service.StreamingService
import com.wireless.audiostreamer.util.AppPrefs
import com.wireless.audiostreamer.util.MediaSaver
import java.io.File
import kotlin.concurrent.thread

class MainActivity : BaseActivity() {

    private lateinit var tvRole: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvMyInfo: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var panelWifi: View
    private lateinit var tvNoPeers: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var panelBluetooth: View
    private lateinit var tvNoBt: TextView
    private lateinit var btList: LinearLayout
    private lateinit var btnStartListen: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnSenderReady: Button
    private lateinit var sectionFeatures: View
    private lateinit var seekVolume: SeekBar
    private lateinit var tvFeature: TextView
    private lateinit var imgCamera: ImageView
    private var selectedStream = Protocol.STREAM_MIC
    private var pendingPeerIp: String? = null
    private var pendingBtAddr: String? = null
    private var micRecorder: MicRecorder? = null
    private var voiceRecorder: MicRecorder? = null
    private var videoWriter: ReceiverVideoWriter? = null
    private var senderPermsOk = false

    private val ui = Handler(Looper.getMainLooper())
    private var discovery: DiscoveryHelper? = null
    private var peers: List<DiscoveryHelper.Peer> = emptyList()
    private var tab = 0
    private var running = false
    private var resumed = false
    private var createdLang = "system"
    private lateinit var audioManager: AudioManager

    private class PendingPerm(val must: List<String>, val onOk: () -> Unit)
    private class StartReq(val mode: String, val peerIp: String?, val btAddr: String?)
    private var pendingPerm: PendingPerm? = null
    private var startReq: StartReq? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val p = pendingPerm; pendingPerm = null
            if (p != null) {
                val bad = p.must.firstOrNull { !granted(it) }
                if (bad == null) { senderPermsOk = true; p.onOk() } else toast(deniedMessage(bad))
            }
        }

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val req = startReq; startReq = null
            val data = r.data
            if (req != null && r.resultCode == RESULT_OK && data != null)
                launchService(req.mode, req.peerIp, req.btAddr, r.resultCode, data)
            else toast(R.string.cast_denied)
        }

    private val cameraReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jpeg = intent?.getByteArrayExtra("jpeg") ?: return
            videoWriter?.writeJpeg(jpeg)
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            ui.post { imgCamera.visibility = View.VISIBLE; imgCamera.setImageBitmap(bmp) }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            applyStatus(
                intent.getBooleanExtra(StreamingService.EXTRA_RUNNING, false),
                intent.getBooleanExtra(StreamingService.EXTRA_CONNECTED, false),
                intent.getStringExtra(StreamingService.EXTRA_TEXT) ?: "",
                intent.getBooleanExtra(StreamingService.EXTRA_ERROR, false)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppPrefs.isConfigured(this)) {
            startActivity(Intent(this, RoleSetupActivity::class.java)); finish(); return
        }
        createdLang = AppPrefs.lang(this)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        audioManager = getSystemService(AudioManager::class.java)

        tvRole = findViewById(R.id.tvRole)
        tvStatus = findViewById(R.id.tvStatus)
        tvMyInfo = findViewById(R.id.tvMyInfo)
        tabLayout = findViewById(R.id.tabLayout)
        panelWifi = findViewById(R.id.panelWifi)
        tvNoPeers = findViewById(R.id.tvNoPeers)
        peerList = findViewById(R.id.peerList)
        panelBluetooth = findViewById(R.id.panelBluetooth)
        tvNoBt = findViewById(R.id.tvNoBt)
        btList = findViewById(R.id.btList)
        btnStartListen = findViewById(R.id.btnStartListen)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnSenderReady = findViewById(R.id.btnSenderReady)
        sectionFeatures = findViewById(R.id.sectionFeatures)
        seekVolume = findViewById(R.id.seekVolume)
        tvFeature = findViewById(R.id.tvFeature)
        imgCamera = findViewById(R.id.imgCamera)

        findViewById<Button>(R.id.btnMicRecv).setOnClickListener { openFeature(Protocol.STREAM_MIC) }
        findViewById<Button>(R.id.btnMicStop).setOnClickListener { stopFeature() }
        findViewById<Button>(R.id.btnMicRecord).setOnClickListener { startMicRecord() }
        findViewById<Button>(R.id.btnMicRecordStop).setOnClickListener { stopMicRecord() }

        findViewById<Button>(R.id.btnCamRecv).setOnClickListener {
            openFeature(Protocol.STREAM_CAMERA_BACK); imgCamera.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.btnCamStop).setOnClickListener {
            stopFeature(); imgCamera.visibility = View.GONE
        }
        findViewById<Button>(R.id.btnCamPhoto).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.btnCamVideo).setOnClickListener { startVideoRecord() }
        findViewById<Button>(R.id.btnCamVideoStop).setOnClickListener { stopVideoRecord() }

        findViewById<Button>(R.id.btnCamFrontRecv).setOnClickListener {
            openFeature(Protocol.STREAM_CAMERA_FRONT); imgCamera.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.btnCamFrontStop).setOnClickListener {
            stopFeature(); imgCamera.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnStorageRecv).setOnClickListener {
            openFeature(Protocol.STREAM_STORAGE); openStorageBrowser()
        }
        findViewById<Button>(R.id.btnStorageStop).setOnClickListener { stopFeature() }

        findViewById<Button>(R.id.btnSysRecv).setOnClickListener { openFeature(Protocol.STREAM_SYSTEM) }
        findViewById<Button>(R.id.btnSysStop).setOnClickListener { stopFeature() }

        findViewById<Button>(R.id.btnSysMicRecv).setOnClickListener { openFeature(Protocol.STREAM_COMBO) }
        findViewById<Button>(R.id.btnSysMicStop).setOnClickListener { stopFeature() }
        findViewById<Button>(R.id.btnVoiceRecord).setOnClickListener { startVoiceRecord() }
        findViewById<Button>(R.id.btnVoiceRecordStop).setOnClickListener { stopVoiceRecord() }

        btnSenderReady.setOnClickListener { prepareSenderAllPerms() }
        findViewById<Button>(R.id.btnInternet).setOnClickListener { connectInternet() }
        findViewById<Button>(R.id.btnInternetStop).setOnClickListener { stopFeature() }

        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_wifi))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_bt))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { tab = t.position; updateUi() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        findViewById<Button>(R.id.btnRefreshBt).setOnClickListener { refreshBt() }
        findViewById<Button>(R.id.btnBtSettings).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
        }
        btnStartListen.setOnClickListener {
            startReceiver(if (tab == 0) StreamingService.MODE_WIFI else StreamingService.MODE_BT, selectedStream)
        }
        btnDisconnect.setOnClickListener {
            stopService(Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_STOP))
        }

        seekVolume.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        seekVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java)); return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        if (!AppPrefs.isConfigured(this)) return
        if (AppPrefs.lang(this) != createdLang) { recreate(); return }
        resumed = true
        ContextCompat.registerReceiver(this, statusReceiver, IntentFilter(StreamingService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, cameraReceiver, IntentFilter("com.wireless.audiostreamer.CAMERA_FRAME"), ContextCompat.RECEIVER_NOT_EXPORTED)
        seekVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        tvMyInfo.text = getString(R.string.my_ip, NetUtil.localIps().joinToString(", ").ifEmpty { "—" })
        applyStatus(StreamingService.running, StreamingService.lastConnected, StreamingService.lastText, StreamingService.lastError)
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(cameraReceiver) } catch (_: Exception) {}
        stopDiscovery()
    }

    private fun isSender() = AppPrefs.role(this) == AppPrefs.ROLE_SENDER

    private fun updateUi() {
        val sender = isSender()
        tvRole.text = getString(R.string.role_fmt, getString(if (sender) R.string.role_sender else R.string.role_receiver))
        sectionFeatures.visibility = if (sender) View.GONE else View.VISIBLE
        btnSenderReady.visibility = if (sender && !running) View.VISIBLE else View.GONE
        panelWifi.visibility = if (tab == 0) View.VISIBLE else View.GONE
        panelBluetooth.visibility = if (tab == 1) View.VISIBLE else View.GONE
        btnStartListen.setText(if (tab == 0) R.string.start_listening_wifi else R.string.start_listening_bt)
        btnStartListen.visibility = if (!sender && !running) View.VISIBLE else View.GONE
        if (tab == 0) renderPeers() else refreshBt()
    }

    private fun applyStatus(run: Boolean, connected: Boolean, text: String, error: Boolean) {
        running = run
        tvStatus.text = if (text.isEmpty()) getString(R.string.status_idle) else text
        tvStatus.setTextColor(Color.parseColor(when {
            error -> "#C62828"; connected -> "#2E7D32"; run -> "#F9A825"; else -> "#888888"
        }))
        btnDisconnect.visibility = if (run) View.VISIBLE else View.GONE
        btnStartListen.visibility = if (!isSender() && !run) View.VISIBLE else View.GONE
        btnSenderReady.visibility = if (isSender() && !run) View.VISIBLE else View.GONE
        if (run) stopDiscovery() else if (resumed) startDiscovery()
    }

    private fun prepareSenderAllPerms() {
        val (all, must) = allPerms()
        requestPerms(all, must) {
            senderPermsOk = true
            toast(R.string.sender_ready_ok)
            startDiscovery()
            updateUi()
        }
    }

    private fun allPerms(): Pair<List<String>, List<String>> {
        val all = ArrayList<String>(); val must = ArrayList<String>()
        all.add(Manifest.permission.RECORD_AUDIO); must.add(Manifest.permission.RECORD_AUDIO)
        all.add(Manifest.permission.CAMERA); must.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            all.add(Manifest.permission.READ_MEDIA_AUDIO); must.add(Manifest.permission.READ_MEDIA_AUDIO)
            all.add(Manifest.permission.POST_NOTIFICATIONS); must.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            all.add(Manifest.permission.READ_EXTERNAL_STORAGE); must.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 32) {
                all.add(Manifest.permission.WRITE_EXTERNAL_STORAGE); must.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) all.add(Manifest.permission.BLUETOOTH_CONNECT)
        return all to must
    }

    private fun renderPeers() {
        peerList.removeAllViews()
        if (!isSender()) {
            tvNoPeers.setText(R.string.receiver_wifi_hint); tvNoPeers.visibility = View.VISIBLE; return
        }
        val list = peers.filter { it.role == AppPrefs.ROLE_RECEIVER }
        val manual = AppPrefs.manualIp(this)
        tvNoPeers.setText(R.string.no_peers)
        tvNoPeers.visibility = if (list.isEmpty() && manual.isBlank()) View.VISIBLE else View.GONE
        for (p in list) {
            val label = if (p.stream.isNotBlank()) "${p.name}\n${p.ip}\n→ ${streamLabel(p.stream)}" else "${p.name}\n${p.ip}"
            addButton(peerList, label) {
                pendingPeerIp = p.ip
                connectWithFallback(p.stream.ifBlank { selectedStream }, p.ip)
            }
        }
        if (manual.isNotBlank() && list.none { it.ip == manual }) {
            addButton(peerList, getString(R.string.manual_peer_fmt, manual)) {
                pendingPeerIp = manual
                connectWithFallback(selectedStream, manual)
            }
        }
        if (!running && senderPermsOk) {
            val want = list.firstOrNull { it.stream.isNotBlank() } ?: list.firstOrNull()
            if (want != null) {
                pendingPeerIp = want.ip
                connectWithFallback(want.stream.ifBlank { selectedStream }, want.ip)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBt() {
        btList.removeAllViews(); tvNoBt.visibility = View.GONE
        if (!isSender()) { tvNoBt.setText(R.string.receiver_bt_hint); tvNoBt.visibility = View.VISIBLE; return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPerms(listOf(Manifest.permission.BLUETOOTH_CONNECT), listOf(Manifest.permission.BLUETOOTH_CONNECT)) { refreshBt() }
            return
        }
        val adapter = btAdapter()
        if (adapter == null || !adapter.isEnabled) { tvNoBt.setText(R.string.status_bt_off); tvNoBt.visibility = View.VISIBLE; return }
        val devices = try { adapter.bondedDevices } catch (e: SecurityException) { null }
        if (devices.isNullOrEmpty()) { tvNoBt.setText(R.string.no_bt_devices); tvNoBt.visibility = View.VISIBLE; return }
        for (d in devices) {
            val name = (try { d.name } catch (e: SecurityException) { null }) ?: "?"
            addButton(btList, "$name\n${d.address}") {
                pendingBtAddr = d.address
                startSenderAfterPerms(StreamingService.MODE_BT, null, d.address, selectedStream)
            }
        }
        if (!running && senderPermsOk && devices != null && devices.size == 1) {
            val d = devices.first()
            pendingBtAddr = d.address
            startSenderAfterPerms(StreamingService.MODE_BT, null, d.address, selectedStream)
        }
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = text; b.isAllCaps = false; b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (8 * resources.displayMetrics.density).toInt()
        parent.addView(b, lp)
    }

    private fun startDiscovery() {
        if (running || !AppPrefs.isConfigured(this)) return
        val streamAnnounce = if (isSender()) "" else selectedStream
        if (discovery != null) { discovery?.setStream(streamAnnounce); return }
        val d = DiscoveryHelper(applicationContext, PacketCipher(AppPrefs.pin(this)))
        d.onPeers = { list ->
            ui.post {
                peers = list
                maybeAutoRoleSwap(list)
                if (resumed && tab == 0) renderPeers()
            }
        }
        d.start(AppPrefs.deviceName(), AppPrefs.role(this), AppPrefs.manualIp(this), streamAnnounce)
        discovery = d
    }

    private fun stopDiscovery() { discovery?.stop(); discovery = null; peers = emptyList() }

    private fun stopFeature() {
        val i = Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_STOP_FEATURE)
        try { startService(i) } catch (_: Exception) {
            stopService(Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_STOP))
        }
        selectedStream = Protocol.STREAM_MIC
        discovery?.setStream("")
        tvFeature.text = getString(R.string.feature_active, "—")
    }

    private fun openFeature(stream: String) {
        if (isSender()) { toast(R.string.receiver_only_features); return }
        selectedStream = stream
        tvFeature.text = getString(R.string.feature_active, streamLabel(stream))
        discovery?.setStream(stream)
        if (discovery == null) startDiscovery()
        val mode = if (tab == 0) StreamingService.MODE_WIFI else StreamingService.MODE_BT
        startReceiver(mode, stream)
    }

    private fun maybeAutoRoleSwap(list: List<DiscoveryHelper.Peer>) {
        if (!isSender()) return
        val myName = AppPrefs.deviceName()
        val otherSender = list.firstOrNull { it.role == AppPrefs.ROLE_SENDER }
        if (otherSender != null && otherSender.name.isNotBlank() && otherSender.name < myName) {
            AppPrefs.setRole(this, AppPrefs.ROLE_RECEIVER)
            toast(R.string.role_swapped_to_receiver)
            updateUi()
            startDiscovery()
        }
    }

    private fun connectInternet() {
        val host = findViewById<EditText>(R.id.etRemoteHost).text?.toString()?.trim().orEmpty()
        if (host.isBlank()) { toast(R.string.internet_host_required); return }
        AppPrefs.setManualIp(this, host)
        pendingPeerIp = host
        if (isSender()) {
            startSenderAfterPerms(StreamingService.MODE_INTERNET, host, null, selectedStream)
        } else {
            openFeatureInternet(selectedStream, host)
        }
    }

    private fun openFeatureInternet(stream: String, host: String) {
        selectedStream = stream
        tvFeature.text = getString(R.string.feature_active, streamLabel(stream))
        val (all, must) = allPerms()
        requestPerms(all, must) { launchService(StreamingService.MODE_INTERNET, host, null, 0, null) }
    }

    private fun connectWithFallback(stream: String, preferredIp: String?) {
        selectedStream = stream
        val wifiIp = preferredIp ?: pendingPeerIp ?: AppPrefs.manualIp(this).ifBlank { null }
        if (!wifiIp.isNullOrBlank() && NetUtil.isPrivateIp(wifiIp)) {
            startSenderAfterPerms(StreamingService.MODE_WIFI, wifiIp, null, stream)
            ui.postDelayed({
                if (!StreamingService.lastConnected) tryBtFallback(stream)
            }, 6000)
        } else tryBtFallback(stream)
    }

    private fun tryBtFallback(stream: String) {
        val addr = pendingBtAddr
        if (!addr.isNullOrBlank()) {
            startSenderAfterPerms(StreamingService.MODE_BT, null, addr, stream)
            ui.postDelayed({
                if (!StreamingService.lastConnected) {
                    val host = AppPrefs.manualIp(this)
                    if (host.isNotBlank() && !NetUtil.isPrivateIp(host))
                        startSenderAfterPerms(StreamingService.MODE_INTERNET, host, null, stream)
                }
            }, 6000)
        } else {
            val host = AppPrefs.manualIp(this)
            if (host.isNotBlank())
                startSenderAfterPerms(StreamingService.MODE_INTERNET, host, null, stream)
        }
    }

    private fun startSenderAfterPerms(mode: String, peerIp: String?, btAddr: String?, stream: String) {
        selectedStream = stream
        tvFeature.text = getString(R.string.feature_active, streamLabel(stream))
        val (all, must) = allPerms()
        requestPerms(all, must) { senderPermsOk = true; startSender(mode, peerIp, btAddr, stream) }
    }

    private fun startSender(mode: String, peerIp: String?, btAddr: String?, stream: String) {
        selectedStream = stream
        if (stream == Protocol.STREAM_SYSTEM || stream == Protocol.STREAM_COMBO) {
            startReq = StartReq(mode, peerIp, btAddr)
            projLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } else launchService(mode, peerIp, btAddr, 0, null)
    }

    private fun startReceiver(mode: String, stream: String) {
        selectedStream = stream
        val (all, must) = allPerms()
        requestPerms(all, must) { launchService(mode, null, null, 0, null) }
    }

    private fun launchService(mode: String, peerIp: String?, btAddr: String?, code: Int, data: Intent?) {
        val i = Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_START)
            .putExtra(StreamingService.EXTRA_ROLE, AppPrefs.role(this))
            .putExtra(StreamingService.EXTRA_MODE, mode)
            .putExtra(StreamingService.EXTRA_STREAM, selectedStream)
        if (peerIp != null) i.putExtra(StreamingService.EXTRA_PEER_IP, peerIp)
        if (btAddr != null) i.putExtra(StreamingService.EXTRA_BT_ADDRESS, btAddr)
        if (data != null) {
            i.putExtra(StreamingService.EXTRA_RESULT_CODE, code)
            i.putExtra(StreamingService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, i)
    }

    private fun startMicRecord() {
        if (micRecorder?.isRecording == true) return
        val r = MicRecorder(this)
        if (r.start()) { micRecorder = r; toast(R.string.recording) } else toast(R.string.status_capture_failed)
    }

    private fun stopMicRecord() {
        val path = micRecorder?.stop(); micRecorder = null
        if (path != null) Toast.makeText(this, getString(R.string.saved_ok, path), Toast.LENGTH_LONG).show()
    }

    private fun startVoiceRecord() {
        if (voiceRecorder?.isRecording == true) return
        val r = MicRecorder(this)
        if (r.start()) { voiceRecorder = r; toast(R.string.recording) } else toast(R.string.status_capture_failed)
    }

    private fun stopVoiceRecord() {
        val path = voiceRecorder?.stop(); voiceRecorder = null
        if (path != null) Toast.makeText(this, getString(R.string.saved_ok, path), Toast.LENGTH_LONG).show()
    }

    private fun startVideoRecord() {
        if (videoWriter?.isRecording == true) return
        val w = ReceiverVideoWriter(this)
        val path = w.start()
        if (path != null) {
            videoWriter = w
            toast(R.string.recording)
        } else toast(R.string.status_capture_failed)
    }

    private fun stopVideoRecord() {
        val path = videoWriter?.stop()
        videoWriter = null
        if (path != null) Toast.makeText(this, getString(R.string.saved_ok, path), Toast.LENGTH_LONG).show()
    }

    private fun takePhoto() {
        imgCamera.isDrawingCacheEnabled = true; imgCamera.buildDrawingCache()
        val bmp = try { imgCamera.drawingCache } catch (_: Exception) { null }
        if (bmp != null) {
            val path = MediaSaver.saveBitmap(this, bmp)
            if (path != null) Toast.makeText(this, getString(R.string.saved_ok, path), Toast.LENGTH_LONG).show()
        } else toast(R.string.cam_live)
        imgCamera.isDrawingCacheEnabled = false
    }

    private fun openStorageBrowser() {
        val list = findViewById<LinearLayout>(R.id.storageList)
        list.removeAllViews()
        val dir = FileTransfer.listDir(this)
        val localFiles = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        for (f in localFiles) addStorageRow(list, f.name, false, null)
        val host = pendingPeerIp
            ?: peers.firstOrNull { it.role == AppPrefs.ROLE_SENDER }?.ip
            ?: StreamingService.lastFromIp.takeIf { it.isNotBlank() && it != "Bluetooth" && it != "OK" }
        if (!host.isNullOrBlank()) {
            pendingPeerIp = host
            thread(isDaemon = true) {
                try {
                    val remote = FileTransfer.listRemote(host!!)
                    ui.post {
                        for (name in remote) addStorageRow(list, name, true, host)
                        if (localFiles.isEmpty() && remote.isEmpty())
                            list.addView(TextView(this).also { it.text = getString(R.string.storage_empty) })
                    }
                } catch (_: Exception) {
                    ui.post {
                        if (localFiles.isEmpty())
                            list.addView(TextView(this).also { it.text = getString(R.string.storage_empty) })
                    }
                }
            }
        } else if (localFiles.isEmpty()) {
            list.addView(TextView(this).also { it.text = getString(R.string.storage_empty) })
        }
    }

    private fun addStorageRow(list: LinearLayout, name: String, isRemote: Boolean, host: String?) {
        val row = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).also {
            it.text = if (isRemote) "☁ $name" else name
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (isRemote && host != null) {
            row.addView(Button(this).also {
                it.text = getString(R.string.file_download)
                it.setOnClickListener {
                    thread(isDaemon = true) {
                        val dest = File(FileTransfer.listDir(this), name.substringAfterLast('/'))
                        val ok = try { FileTransfer.download(host, name, dest) } catch (_: Exception) { false }
                        ui.post {
                            if (ok) Toast.makeText(this, getString(R.string.saved_ok, dest.absolutePath), Toast.LENGTH_LONG).show()
                            openStorageBrowser()
                        }
                    }
                }
            })
            row.addView(Button(this).also {
                it.text = getString(R.string.file_delete)
                it.setOnClickListener {
                    thread(isDaemon = true) {
                        try { FileTransfer.deleteRemote(host, name) } catch (_: Exception) {}
                        ui.post { openStorageBrowser() }
                    }
                }
            })
        } else {
            row.addView(Button(this).also {
                it.text = getString(R.string.file_delete)
                it.setOnClickListener {
                    File(FileTransfer.listDir(this), name).delete(); openStorageBrowser()
                }
            })
        }
        list.addView(row)
    }

    private fun streamLabel(stream: String): String = when (stream) {
        Protocol.STREAM_MIC -> getString(R.string.feat_mic)
        Protocol.STREAM_CAMERA_FRONT -> getString(R.string.feat_cam_front)
        Protocol.STREAM_CAMERA_BACK -> getString(R.string.feat_camera)
        Protocol.STREAM_STORAGE -> getString(R.string.feat_storage)
        Protocol.STREAM_COMBO -> getString(R.string.feat_sys_mic)
        else -> getString(R.string.feat_system)
    }

    private fun requestPerms(all: List<String>, must: List<String>, onOk: () -> Unit) {
        val need = all.filter { !granted(it) }
        if (need.isEmpty()) onOk()
        else { pendingPerm = PendingPerm(must, onOk); permLauncher.launch(need.toTypedArray()) }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun deniedMessage(p: String) = when (p) {
        Manifest.permission.RECORD_AUDIO -> R.string.perm_audio
        Manifest.permission.CAMERA -> R.string.perm_camera
        Manifest.permission.POST_NOTIFICATIONS -> R.string.perm_notif
        else -> R.string.perm_storage
    }
    private fun btAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()
}
