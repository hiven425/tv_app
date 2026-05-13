package com.tvcast.app.dial

import android.content.Context
import android.util.Log
import com.tvcast.app.util.NetworkUtils

/**
 * DIAL (Discovery And Launch) receiver.
 *
 * DIAL is the protocol YouTube/Netflix apps use for "send to TV" — when an app on the phone
 * already knows the destination, it tells the TV what to play and the TV launches the corresponding
 * native app. Discovery is via SSDP advertising `urn:dial-multiscreen-org:service:dial:1`; the
 * SSDP NOTIFY/200OK responses carry an `Application-URL` header pointing at this server.
 *
 * The actual SSDP advertisement bits live in [com.tvcast.app.dlna.SsdpServer] (it already runs the
 * UDP 1900 socket). This class owns only the HTTP side.
 */
class DialReceiver(
    private val context: Context,
    private val friendlyName: () -> String,
) {
    companion object {
        private const val TAG = "DialReceiver"
        const val DIAL_PORT = 49216
    }

    private var server: DialServer? = null

    fun start() {
        val ip = NetworkUtils.getLocalIpv4()
        Log.i(TAG, "Starting DIAL receiver on $ip:$DIAL_PORT")
        val s = DialServer(DIAL_PORT, friendlyName) { "http://$ip:$DIAL_PORT" }
        s.start()
        server = s
    }

    fun stop() {
        Log.i(TAG, "Stopping DIAL receiver")
        runCatching { server?.stop() }
        server = null
    }
}
