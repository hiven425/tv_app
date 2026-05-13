package com.tvcast.app.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

object NetworkUtils {

    /** Returns the first non-loopback IPv4 address bound to an "up" interface. */
    fun getLocalIpv4(): String {
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        }
        return "0.0.0.0"
    }

    fun acquireMulticastLock(context: Context, tag: String): WifiManager.MulticastLock? {
        return runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock(tag).apply {
                setReferenceCounted(true)
                acquire()
            }
        }.getOrNull()
    }

    /** Generate a stable UDN derived from device hardware id. */
    fun stableUdn(): String {
        val seed = (Build.MANUFACTURER + Build.MODEL + Build.FINGERPRINT).hashCode().toLong()
        val uuid = UUID(seed, seed xor 0x5A5A5A5AL)
        return "uuid:$uuid"
    }

    fun stableMacBytes(): ByteArray {
        val udn = stableUdn()
        val bytes = ByteArray(6)
        for (i in 0 until 6) {
            bytes[i] = ((udn.hashCode() ushr (i * 4)) and 0xFF).toByte()
        }
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte() // locally administered
        return bytes
    }

    fun macHex(bytes: ByteArray = stableMacBytes()): String =
        bytes.joinToString("") { "%02X".format(it) }
}
