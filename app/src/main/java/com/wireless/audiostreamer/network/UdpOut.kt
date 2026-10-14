package com.wireless.audiostreamer.network

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

/** Outgoing UDP socket that re-creates itself after any network change or error. */
class UdpOut(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private var sock: DatagramSocket? = null

    @Synchronized
    fun send(data: ByteArray, len: Int, addr: InetAddress, port: Int): Boolean {
        return try {
            val s = sock ?: open()
            s.send(DatagramPacket(data, len, addr, port))
            true
        } catch (e: Exception) {
            closeLocked()
            false
        }
    }

    @Synchronized
    fun reset() = closeLocked()

    private fun open(): DatagramSocket {
        val s = DatagramSocket(null as SocketAddress?)
        s.broadcast = true
        NetUtil.bindToWifi(appCtx, s)
        s.bind(InetSocketAddress(0))
        sock = s
        return s
    }

    private fun closeLocked() {
        try { sock?.close() } catch (e: Exception) { }
        sock = null
    }
}
