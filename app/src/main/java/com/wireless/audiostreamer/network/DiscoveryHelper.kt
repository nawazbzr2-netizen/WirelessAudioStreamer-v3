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

/**
 * Announces this phone (name + role) every 2 s and listens for other phones that use the same PIN.
 * Announcements are AES-GCM protected, so phones with a different PIN never show up.
 */
class DiscoveryHelper(ctx: Context, private val cipher: PacketCipher) {
    data class Peer(val ip: String, val name: String, val role: String, val seen: Long)

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

    fun start(name: String, role: String, manualIp: String) {
        if (running) return
        this.name = name
        this.role = role
        this.manualIp = manualIp
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
                if (running) {
                    try { Thread.sleep(1000) } catch (ie: InterruptedException) { return }
                }
            } finally {
                try { s?.close() } catch (e: Exception) { }
                rxSock = null
            }
        }
    }

    private fun handle(pkt: DatagramPacket) {
        val ip = pkt.address?.hostAddress ?: return
        if (!NetUtil.isPrivateIp(ip) || NetUtil.localIps().contains(ip)) return
        val o = cipher.open(pkt.data, pkt.length) ?: return
        if (o.type != Protocol.T_HELLO) return
        val h = Protocol.parseHello(o.plain) ?: return
        val now = System.currentTimeMillis()
        peers[ip] = Peer(ip, h.first, h.second, now)
        unicast[ip] = now
        publish()
    }

    private fun txLoop() {
        while (running) {
            try {
                val frame = cipher.seal(Protocol.T_HELLO, session, seq++, Protocol.helloPlain(name, role))
                val targets = LinkedHashSet<InetAddress>()
                targets.addAll(NetUtil.broadcastAddresses())
                val manual = manualIp
                if (NetUtil.isPrivateIp(manual)) targets.add(InetAddress.getByName(manual))
                val now = System.currentTimeMillis()
                for ((ip, t) in unicast) {
                    if (now - t < 15000) {
                        if (NetUtil.isPrivateIp(ip)) targets.add(InetAddress.getByName(ip))
                    } else {
                        unicast.remove(ip)
                    }
                }
                for (t in targets) out.send(frame, frame.size, t, Protocol.PORT_DISCOVERY)

                var changed = false
                for ((ip, p) in peers) {
                    if (now - p.seen > 7000) {
                        peers.remove(ip)
                        changed = true
                    }
                }
                if (changed) publish()
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                try { Thread.sleep(1000) } catch (ie: InterruptedException) { return }
            }
        }
    }

    private fun publish() {
        onPeers?.invoke(peers.values.sortedBy { it.name })
    }
}
