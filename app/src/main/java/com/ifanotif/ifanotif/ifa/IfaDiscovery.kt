package com.ifanotif.ifanotif.ifa

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

data class IfaHost(val name: String, val address: String, val port: Int) {
    val url: String get() = "ws://$address:$port/phone"
}

/**
 * Finds Ifa on the local network: broadcast a probe on UDP 8788, Ifa answers with its
 * name and WebSocket port. No reply means the PC (or Ifa) isn't running.
 */
object IfaDiscovery {
    const val DISCOVERY_PORT = 8788
    const val DEFAULT_PORT = 8789
    private val PROBE = "IFA_DISCOVER_V1".toByteArray()

    fun discover(context: Context, timeoutMs: Int = 1500): IfaHost? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            for (address in broadcastAddresses(context)) {
                try {
                    socket.send(DatagramPacket(PROBE, PROBE.size, address, DISCOVERY_PORT))
                } catch (_: Exception) {
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    return null
                }
                val reply = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
                if (reply.optString("service") != "ifa") continue
                return IfaHost(
                    name = reply.optString("name", "PC"),
                    address = packet.address.hostAddress ?: continue,
                    port = reply.optInt("port", DEFAULT_PORT)
                )
            }
        }
        return null
    }

    /** "192.168.1.97" or "192.168.1.97:8789". */
    fun parseManual(text: String): IfaHost? {
        val trimmed = text.trim().removePrefix("ws://").removePrefix("http://").substringBefore("/")
        if (trimmed.isEmpty()) return null
        val host = trimmed.substringBefore(":")
        val port = trimmed.substringAfter(":", "").toIntOrNull() ?: DEFAULT_PORT
        return IfaHost(name = host, address = host, port = port)
    }

    private fun broadcastAddresses(context: Context): List<InetAddress> {
        val addresses = mutableListOf(InetAddress.getByName("255.255.255.255"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val link = connectivity?.getLinkProperties(connectivity.activeNetwork)
        link?.linkAddresses?.forEach { linkAddress ->
            val ip = linkAddress.address as? Inet4Address ?: return@forEach
            val prefix = linkAddress.prefixLength
            if (prefix !in 1..30) return@forEach
            val bytes = ip.address
            val value = bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val broadcast = value or (-1 ushr prefix)
            addresses += InetAddress.getByAddress(ByteArray(4) { i -> (broadcast shr (24 - 8 * i)).toByte() })
        }
        return addresses
    }
}
