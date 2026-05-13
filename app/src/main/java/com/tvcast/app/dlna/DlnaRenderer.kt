package com.tvcast.app.dlna

import android.content.Context
import android.util.Log
import com.tvcast.app.util.NetworkUtils

/**
 * Orchestrates the DLNA receiver: spins up an HTTP server on [HTTP_PORT] for the UPnP
 * description + SOAP control, and an SSDP server for discovery.
 */
class DlnaRenderer(
    private val context: Context,
    private val friendlyName: () -> String,
    private val dialApplicationUrl: () -> String? = { null },
) {
    companion object {
        private const val TAG = "DlnaRenderer"
        const val HTTP_PORT = 49215
    }

    private val state = RendererState()
    private val udn = NetworkUtils.stableUdn()
    private var ssdp: SsdpServer? = null
    private var http: UpnpHttpServer? = null

    val rendererState: RendererState get() = state

    fun start() {
        val ip = NetworkUtils.getLocalIpv4()
        Log.i(TAG, "Starting DLNA renderer on $ip:$HTTP_PORT, UDN=$udn")

        val server = UpnpHttpServer(
            httpPort = HTTP_PORT,
            friendlyName = friendlyName,
            udn = udn,
            baseUrl = { "http://$ip:$HTTP_PORT" },
            state = state,
        )
        server.start()
        http = server

        val s = SsdpServer(
            localIp = ip,
            httpPort = HTTP_PORT,
            udn = udn,
            dialApplicationUrl = dialApplicationUrl,
        )
        s.start()
        ssdp = s
    }

    fun stop() {
        Log.i(TAG, "Stopping DLNA renderer")
        runCatching { ssdp?.stop() }
        runCatching { http?.stop() }
        ssdp = null
        http = null
    }
}
