package com.wireless.audiostreamer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetUtil {
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Only local-network addresses (10.x, 172.16-31.x, 192.168.x, 169.254.x) are ever accepted. */
    fun isPrivateIp(ip: String?): Boolean {
        if (ip == null || !IPV4.matches(ip)) return false
        return try {
            val a = InetAddress.getByName(ip) // literal address: no DNS lookup
            a is Inet4Address && !a.isLoopbackAddress && (a.isSiteLocalAddress || a.isLinkLocalAddress)
        } catch (e: Exception) {
            false
        }
    }

    fun localIps(): List<String> {
        val out = ArrayList<String>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in all.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && (a.isSiteLocalAddress || a.isLinkLocalAddress)) {
                        a.hostAddress?.let { out.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in all.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    val b = ia.broadcast
                    if (b != null && a is Inet4Address && (a.isSiteLocalAddress || a.isLinkLocalAddress)) {
                        out.add(b)
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out.toList()
    }

    /**
     * Sends this socket's traffic through the Wi-Fi network even when it has no internet
     * (otherwise Android may route a hotspot client's packets over mobile data).
     * On the phone that hosts the hotspot there is no Wi-Fi client network, so nothing is bound.
     */
    @Suppress("DEPRECATION")
    fun bindToWifi(ctx: Context, s: DatagramSocket) {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    n.bindSocket(s)
                    return
                }
            }
        } catch (e: Exception) {
        }
    }
}
