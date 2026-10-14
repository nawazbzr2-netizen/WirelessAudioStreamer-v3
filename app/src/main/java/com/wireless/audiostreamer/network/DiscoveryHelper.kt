package com.wireless.audiostreamer.network

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class DiscoveryHelper(ctx: Context, private val cipher: PacketCipher) {
    data class Peer(val ip: String, val name: String, val role: String, val seen: Long, val stream: String = "")

    @Volatile var onPeers: ((List<Peer>) -> Unit)? = null

    private val out = UdpOut(ctx)
    private val peers = ConcurrentHashMap<String, Peer>()
    private val unicast = ConcurrentHashMap<String, Long>()
    private val session = SecureRandom().nextLong()
    private var seq = 0
    @Volatile private var running = false
    @Volatile private var rxSock: DatagramSocket? = null
    private val threads = ArrayList<Thread>()
    @Volatile private var manualIp = ""
    private var name = ""
    private var role = ""
    @Volatile private var streamReq = ""

    fun start(name: String, role: String, manualIp: String, stream: String = "") {
        if (running) {
            this.streamReq = stream
            return
        }
        this.name = name
        this.role = role
        this.manualIp = manualIp
        this.streamReq = stream
        running = true
        threads.add(thread(isDaemon = true, name = "was-disc-rx") { rxLoop() })
        threads.add(thread(isDaemon = true, name = "was-disc-tx") { txLoop() })
    }

    fun stop() {
        running = false
        try { rxSock?.close() } catch (e: Exception) { }
        out.reset()
        for (t in threads) t.interrupt()
        threads.clear()
    }

    fun resetNetwork() = out.reset()

    fun setStream(stream: String) { streamReq = stream }

    fun lastSeen(ip: String): Long = peers[ip]?.seen ?: 0L

    fun peer(ip: String): Peer? = peers[ip]

    private fun rxLoop() {
        val buf = ByteArray(1024)
        while (running) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null as SocketAddress?)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(Protocol.PORT_DISCOVERY))
                rxSock = s
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    handle(pkt)
                }
            } catch (e: Exception) {
                if (running) try { Thread.sleep(500) } catch (_: InterruptedException) { return }
            } finally {
                try { s?.close() } catch (_: Exception) { }
                rxSock = null
            }
        }
    }

    private fun handle(pkt: DatagramPacket) {
        val from = pkt.address?.hostAddress ?: return
        if (!NetUtil.isPrivateIp(from)) return
        val o = cipher.open(pkt.data, pkt.length) ?: return
        if (o.type != Protocol.T_HELLO) return
        val h = Protocol.parseHello(o.plain) ?: return
        val (hName, hRole, hStream) = h
        val now = System.currentTimeMillis()
        peers[from] = Peer(from, hName, hRole, now, hStream)
        notifyPeers()
    }

    private fun txLoop() {
        while (running) {
            try {
                val frame = cipher.seal(Protocol.T_HELLO, session, seq++, Protocol.helloPlain(name, role, streamReq))
                val targets = ArrayList<InetAddress>()
                try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) { }
                for (ip in NetUtil.broadcastIps()) {
                    try { targets.add(InetAddress.getByName(ip)) } catch (_: Exception) { }
                }
                if (manualIp.isNotBlank()) {
                    try { targets.add(InetAddress.getByName(manualIp)) } catch (_: Exception) { }
                }
                for (t in targets) out.send(frame, frame.size, t, Protocol.PORT_DISCOVERY)
                val now = System.currentTimeMillis()
                var changed = false
                for ((ip, p) in peers) {
                    if (now - p.seen > 7000) {
                        peers.remove(ip)
                        changed = true
                    }
                }
                if (changed) notifyPeers()
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun notifyPeers() {
        onPeers?.invoke(peers.values.sortedBy { it.name })
    }
}
