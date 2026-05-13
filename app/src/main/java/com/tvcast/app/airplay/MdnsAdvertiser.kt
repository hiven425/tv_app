package com.tvcast.app.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.tvcast.app.util.NetworkUtils
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises the TV as an AirPlay receiver via Bonjour/mDNS.
 *
 *  _airplay._tcp.local. — for the AirPlay video / mirroring entry point
 *  _raop._tcp.local.    — RAOP (Remote Audio Output Protocol) for audio streaming
 *
 * iPhone's Control Center "Screen Mirroring" / Music app's "AirPlay" picker scan these.
 */
class MdnsAdvertiser(
    private val context: Context,
    private val deviceName: String,
    private val airplayPort: Int,
    private val raopPort: Int,
) {
    companion object {
        private const val TAG = "MdnsAdvertiser"
    }

    private var jmdns: JmDNS? = null
    private var lock: WifiManager.MulticastLock? = null
    private val deviceId = NetworkUtils.macHex().chunked(2).joinToString(":")

    fun start() {
        lock = NetworkUtils.acquireMulticastLock(context, "tvcast-mdns")
        val ip = NetworkUtils.getLocalIpv4()
        val addr = InetAddress.getByName(ip)
        try {
            jmdns = JmDNS.create(addr, deviceName)

            // _airplay._tcp — AirPlay 1 video / SetAVTransportURI surface.
            val airplayProps = mapOf(
                "deviceid" to deviceId,
                "features" to "0x527FFFF7,0x1E",        // AirPlay 1 audio + video, no encryption required
                "flags" to "0x4",
                "model" to "AppleTV3,2",
                "srcvers" to "220.68",
                "vv" to "2",
                "pi" to deviceId,
                "pk" to "",
            )
            val airplayInfo = ServiceInfo.create(
                /* type */ "_airplay._tcp.local.",
                /* name */ deviceName,
                /* port */ airplayPort,
                /* weight */ 0,
                /* priority */ 0,
                /* props */ airplayProps,
            )
            jmdns?.registerService(airplayInfo)
            Log.i(TAG, "Registered _airplay._tcp '$deviceName' on $ip:$airplayPort")

            // _raop._tcp — RAOP audio. Service name MUST be "<mac-hex>@<DeviceName>".
            val raopName = "${NetworkUtils.macHex()}@$deviceName"
            val raopProps = mapOf(
                "txtvers" to "1",
                "ch" to "2",                          // 2 channels
                "cn" to "0,1",                        // codecs: PCM, ALAC — decoder lives in AlacDecoder.kt
                "et" to "0",                          // encryption: none (no FairPlay handshake required)
                "sv" to "false",
                "da" to "true",
                "sr" to "44100",                      // sample rate
                "ss" to "16",                         // sample size
                "pw" to "false",                      // no password
                "vn" to "65537",
                "tp" to "UDP",                        // transport
                "md" to "0,1,2",                      // metadata types: text, artwork, progress
                "vs" to "130.14",
                "am" to "AppleTV3,2",
                "sf" to "0x4",
            )
            val raopInfo = ServiceInfo.create(
                "_raop._tcp.local.",
                raopName,
                raopPort,
                0, 0,
                raopProps,
            )
            jmdns?.registerService(raopInfo)
            Log.i(TAG, "Registered _raop._tcp '$raopName' on $ip:$raopPort")

            // _googlecast._tcp — discovery only. Chrome and Cast-aware apps will list this device
            // but won't be able to actually stream until the v0.3 work implements the Cast V2
            // protobuf control protocol on TCP 8009.
            val castId = NetworkUtils.macHex().padEnd(32, '0').lowercase()
            val castProps = mapOf(
                "id" to castId,
                "cd" to castId,
                "rm" to "",
                "ve" to "05",
                "md" to "TvCast",
                "ic" to "/setup/icon.png",
                "fn" to deviceName,
                "ca" to "5",                          // capability mask: video + audio
                "st" to "0",                          // idle
                "bs" to "FA8FCA000000",
                "nf" to "1",
                "rs" to "",
            )
            val castInfo = ServiceInfo.create(
                "_googlecast._tcp.local.",
                deviceName,
                /* port */ 8009,
                0, 0,
                castProps,
            )
            jmdns?.registerService(castInfo)
            Log.i(TAG, "Registered _googlecast._tcp '$deviceName' (discovery only)")
        } catch (e: Exception) {
            Log.e(TAG, "mDNS registration failed", e)
        }
    }

    fun stop() {
        runCatching { jmdns?.unregisterAllServices() }
        runCatching { jmdns?.close() }
        jmdns = null
        runCatching { lock?.release() }
        lock = null
    }
}
