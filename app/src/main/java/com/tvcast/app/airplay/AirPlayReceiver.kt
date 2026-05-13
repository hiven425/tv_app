package com.tvcast.app.airplay

import android.content.Context
import android.util.Log

/** Orchestrates AirPlay: mDNS announcement + HTTP/RTSP server + RAOP audio receiver. */
class AirPlayReceiver(
    private val context: Context,
    private val deviceName: () -> String,
) {
    companion object {
        private const val TAG = "AirPlayReceiver"
        const val AIRPLAY_PORT = 7000
        const val RAOP_PORT = 7000   // Same port — both protocols multiplexed on the AirPlayServer socket
    }

    private val raopAudio = RaopAudioReceiver(context)
    private var server: AirPlayServer? = null
    private var mdns: MdnsAdvertiser? = null

    fun start() {
        Log.i(TAG, "Starting AirPlay receiver on port $AIRPLAY_PORT")
        val s = AirPlayServer(httpPort = AIRPLAY_PORT, raopAudio = raopAudio)
        s.start()
        server = s

        val m = MdnsAdvertiser(
            context = context,
            deviceName = deviceName(),
            airplayPort = AIRPLAY_PORT,
            raopPort = RAOP_PORT,
        )
        m.start()
        mdns = m
    }

    fun stop() {
        Log.i(TAG, "Stopping AirPlay receiver")
        runCatching { mdns?.stop() }
        runCatching { server?.stop() }
        runCatching { raopAudio.stop() }
        mdns = null
        server = null
    }
}
