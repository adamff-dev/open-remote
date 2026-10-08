package com.addev.pcremote

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Busca servidores en la red local mediante broadcast UDP. */
object Discovery {
    private const val PORT = 47001
    private val MAGIC = "PCREMOTE_DISCOVER".toByteArray()

    data class Found(val name: String, val host: String, val port: Int)

    /** Bloqueante (~timeoutMs). Llamar desde un hilo de fondo. */
    fun discover(context: Context, timeoutMs: Long = 1500): List<Found> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("pcremote-discovery").apply { setReferenceCounted(false) }
        val found = LinkedHashMap<String, Found>()
        runCatching { lock.acquire() }
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 250
                val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() >= nextSend) {
                        for (addr in targets) {
                            runCatching { sock.send(DatagramPacket(MAGIC, MAGIC.size, addr, PORT)) }
                        }
                        nextSend = System.currentTimeMillis() + 500
                    }
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val json = JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                        val host = pkt.address.hostAddress ?: continue
                        found[host] = Found(json.optString("name", host), host, json.optInt("port", 47000))
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            }
        } finally {
            runCatching { lock.release() }
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    if (ia.address is Inet4Address) ia.broadcast?.let(out::add)
                }
            }
        }
        return out
    }
}
