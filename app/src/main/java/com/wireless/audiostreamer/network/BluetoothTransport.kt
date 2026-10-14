package com.wireless.audiostreamer.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Classic Bluetooth (RFCOMM) transport. Frames are length-prefixed (2 bytes).
 * Writes go through a small queue on a separate thread so a slow link can never block audio capture;
 * the client side reconnects automatically with back-off.
 * The caller must already hold BLUETOOTH_CONNECT (Android 12+).
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(private val adapter: BluetoothAdapter) {
    interface Listener {
        fun onState(connected: Boolean)
        fun onFrame(buf: ByteArray, len: Int)
    }

    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    private val queue = ArrayBlockingQueue<ByteArray>(40)
    private var mainThread: Thread? = null
    private var writer: Thread? = null

    fun startServer(l: Listener) = begin { serverLoop(l) }

    fun startClient(device: BluetoothDevice, l: Listener) = begin { clientLoop(device, l) }

    private fun begin(body: () -> Unit) {
        running = true
        writer = thread(isDaemon = true, name = "was-bt-writer") { writeLoop() }
        mainThread = thread(isDaemon = true, name = "was-bt") { body() }
    }

    fun send(frame: ByteArray): Boolean {
        if (socket == null) return false
        if (!queue.offer(frame)) {
            queue.poll() // drop the oldest frame instead of delaying live audio
            queue.offer(frame)
        }
        return true
    }

    fun stop() {
        running = false
        closeQuiet(socket)
        closeQuiet(server)
        mainThread?.interrupt()
        writer?.interrupt()
    }

    private fun writeLoop() {
        while (running) {
            val f: ByteArray? = try {
                queue.poll(300, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            }
            if (f == null) continue
            val s = socket ?: continue
            try {
                val pkt = ByteArray(2 + f.size)
                pkt[0] = (f.size shr 8).toByte()
                pkt[1] = f.size.toByte()
                System.arraycopy(f, 0, pkt, 2, f.size)
                val o = s.outputStream
                o.write(pkt)
                o.flush()
            } catch (e: IOException) {
                closeQuiet(s)
            }
        }
    }

    private fun serverLoop(l: Listener) {
        while (running) {
            var srv: BluetoothServerSocket? = null
            try {
                val created: BluetoothServerSocket = adapter.listenUsingRfcommWithServiceRecord("WirelessAudio", APP_UUID)
                srv = created
                server = created
                val s = created.accept()
                closeQuiet(srv)
                server = null
                handle(s, l)
            } catch (e: Exception) {
                if (running) sleepQuiet(1000)
            } finally {
                closeQuiet(srv)
                server = null
            }
        }
    }

    private fun clientLoop(device: BluetoothDevice, l: Listener) {
        var delay = 1500L
        while (running) {
            try {
                try { adapter.cancelDiscovery() } catch (e: Exception) { }
                val s = connectSocket(device, true) ?: connectSocket(device, false)
                if (s != null) {
                    delay = 1500L
                    handle(s, l)
                }
            } catch (e: Exception) {
            }
            if (!running) break
            sleepQuiet(delay)
            delay = minOf(delay * 2, 8000L)
        }
    }

    private fun connectSocket(device: BluetoothDevice, secure: Boolean): BluetoothSocket? {
        return try {
            val sock = if (secure) device.createRfcommSocketToServiceRecord(APP_UUID)
            else device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
            try {
                sock.connect()
                sock
            } catch (e: IOException) {
                closeQuiet(sock)
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun handle(s: BluetoothSocket, l: Listener) {
        socket = s
        queue.clear()
        l.onState(true)
        try {
            val din = DataInputStream(s.inputStream)
            val buf = ByteArray(MAX_FRAME)
            while (running) {
                val len = din.readUnsignedShort()
                if (len <= 0 || len > MAX_FRAME) break
                din.readFully(buf, 0, len)
                l.onFrame(buf, len)
            }
        } catch (e: IOException) {
        } finally {
            closeQuiet(s)
            socket = null
            l.onState(false)
        }
    }

    private fun closeQuiet(c: Closeable?) {
        try { c?.close() } catch (e: Exception) { }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    companion object {
        val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        const val MAX_FRAME = 4096
    }
}
