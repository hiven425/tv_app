package com.tvcast.app.dlna

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Bare-bones SSDP responder + alive announcer.
 *
 * Listens on UDP multicast 239.255.255.250:1900, replies to M-SEARCH for the
 * MediaRenderer device + its services, and periodically broadcasts NOTIFY ssdp:alive.
 *
 * On shutdown sends ssdp:byebye for each NT we own.
 */
class SsdpServer(
    private val localIp: String,
    private val httpPort: Int,
    private val udn: String,
    private val dialApplicationUrl: () -> String? = { null },
) {
    companion object {
        private const val TAG = "SsdpServer"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MAX_AGE = 1800
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: MulticastSocket? = null
    private var jobs = mutableListOf<Job>()

    private val deviceUsn = "$udn::urn:schemas-upnp-org:device:MediaRenderer:1"
    private val rootUsn = "$udn::upnp:rootdevice"
    private val avTransportUsn = "$udn::urn:schemas-upnp-org:service:AVTransport:1"
    private val renderingUsn = "$udn::urn:schemas-upnp-org:service:RenderingControl:1"
    private val connMgrUsn = "$udn::urn:schemas-upnp-org:service:ConnectionManager:1"
    private val dialUsn = "$udn::urn:dial-multiscreen-org:service:dial:1"

    private val advertisements = listOfNotNull(
        "upnp:rootdevice" to rootUsn,
        udn to udn,
        "urn:schemas-upnp-org:device:MediaRenderer:1" to deviceUsn,
        "urn:schemas-upnp-org:service:AVTransport:1" to avTransportUsn,
        "urn:schemas-upnp-org:service:RenderingControl:1" to renderingUsn,
        "urn:schemas-upnp-org:service:ConnectionManager:1" to connMgrUsn,
        "urn:dial-multiscreen-org:service:dial:1" to dialUsn,
    )

    fun start() {
        scope.launch { runListener() }.also(jobs::add)
        scope.launch { runAlivePump() }.also(jobs::add)
    }

    fun stop() {
        runCatching { sendByebye() }
        jobs.forEach(Job::cancel)
        jobs.clear()
        socket?.close()
        socket = null
        scope.cancel()
    }

    private suspend fun runListener() {
        val group = InetAddress.getByName(SSDP_ADDR)
        val sock = MulticastSocket(SSDP_PORT).apply {
            reuseAddress = true
            // joinGroup on a specific interface so multicast goes out the right NIC
            val targetIface: NetworkInterface? = findInterfaceForIp(localIp)
            if (targetIface != null) {
                runCatching { joinGroup(InetSocketAddress(group, SSDP_PORT), targetIface) }
                    .onFailure { runCatching { joinGroup(group) } }
            } else {
                joinGroup(group)
            }
        }
        socket = sock
        val buf = ByteArray(4096)
        while (scope.isActive) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
                    handleMSearch(msg, pkt.address, pkt.port)
                }
            } catch (e: Exception) {
                if (!scope.isActive) break
                Log.w(TAG, "SSDP receive error", e)
            }
        }
    }

    private fun handleMSearch(msg: String, from: InetAddress, fromPort: Int) {
        val st = parseHeader(msg, "ST") ?: return
        val matches: List<Pair<String, String>> = when {
            st.equals("ssdp:all", ignoreCase = true) -> advertisements
            st.equals("upnp:rootdevice", ignoreCase = true) -> listOf("upnp:rootdevice" to rootUsn)
            st == udn -> listOf(udn to udn)
            st.startsWith("urn:schemas-upnp-org:device:MediaRenderer") -> listOf(st to deviceUsn)
            st.startsWith("urn:schemas-upnp-org:service:AVTransport") -> listOf(st to avTransportUsn)
            st.startsWith("urn:schemas-upnp-org:service:RenderingControl") -> listOf(st to renderingUsn)
            st.startsWith("urn:schemas-upnp-org:service:ConnectionManager") -> listOf(st to connMgrUsn)
            st.startsWith("urn:dial-multiscreen-org:service:dial") && dialApplicationUrl() != null ->
                listOf(st to dialUsn)
            else -> return
        }
        val sock = socket ?: return
        for ((stHeader, usn) in matches) {
            val resp = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
                append("DATE: ${httpDate()}\r\n")
                append("EXT:\r\n")
                append("LOCATION: http://$localIp:$httpPort/device.xml\r\n")
                append("SERVER: Android/9.0 UPnP/1.0 TvCast/0.3\r\n")
                append("ST: $stHeader\r\n")
                append("USN: $usn\r\n")
                append("BOOTID.UPNP.ORG: 1\r\n")
                append("CONFIGID.UPNP.ORG: 1\r\n")
                if (stHeader.contains("dial-multiscreen")) {
                    dialApplicationUrl()?.let { append("Application-URL: $it\r\n") }
                }
                append("\r\n")
            }.toByteArray()
            try {
                sock.send(DatagramPacket(resp, resp.size, from, fromPort))
            } catch (e: Exception) {
                Log.w(TAG, "SSDP reply send failed", e)
            }
        }
    }

    private suspend fun runAlivePump() {
        // Initial burst (3x per UPnP spec recommendation)
        repeat(3) { sendAlive(); delay(200) }
        while (scope.isActive) {
            delay((MAX_AGE / 2 * 1000).toLong())
            sendAlive()
        }
    }

    private fun sendAlive() {
        val sock = socket ?: return
        val addr = InetAddress.getByName(SSDP_ADDR)
        for ((nt, usn) in advertisements) {
            val msg = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
                append("LOCATION: http://$localIp:$httpPort/device.xml\r\n")
                append("NT: $nt\r\n")
                append("NTS: ssdp:alive\r\n")
                append("SERVER: Android/9.0 UPnP/1.0 TvCast/0.1\r\n")
                append("USN: $usn\r\n")
                append("BOOTID.UPNP.ORG: 1\r\n")
                append("CONFIGID.UPNP.ORG: 1\r\n")
                append("\r\n")
            }.toByteArray()
            try {
                sock.send(DatagramPacket(msg, msg.size, addr, SSDP_PORT))
            } catch (e: Exception) {
                Log.w(TAG, "SSDP alive send failed", e)
            }
        }
    }

    private fun sendByebye() {
        val sock = socket ?: return
        val addr = InetAddress.getByName(SSDP_ADDR)
        for ((nt, usn) in advertisements) {
            val msg = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("NT: $nt\r\n")
                append("NTS: ssdp:byebye\r\n")
                append("USN: $usn\r\n")
                append("\r\n")
            }.toByteArray()
            try { sock.send(DatagramPacket(msg, msg.size, addr, SSDP_PORT)) } catch (_: Exception) {}
        }
    }

    private fun parseHeader(msg: String, name: String): String? {
        val prefix = "$name:"
        return msg.lineSequence()
            .firstOrNull { it.trim().startsWith(prefix, ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
    }

    private fun httpDate(): String {
        val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return sdf.format(java.util.Date())
    }

    private fun findInterfaceForIp(ip: String): NetworkInterface? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (addr.hostAddress == ip) return iface
            }
        }
        return null
    }
}
