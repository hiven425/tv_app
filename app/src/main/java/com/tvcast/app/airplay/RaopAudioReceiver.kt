package com.tvcast.app.airplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.tvcast.app.util.AudioFocusHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Receives RAOP audio data via UDP and plays it through [AudioTrack].
 *
 * Behaviour:
 *  - Allocates the data/control/timing UDP ports declared in the RTSP SETUP response.
 *  - Decodes incoming RTP audio payloads — ALAC frames are routed through [AlacDecoder] when the
 *    `fmtp` config from the ANNOUNCE SDP was captured; otherwise the payload is treated as raw
 *    16-bit BE PCM and byte-swapped in place.
 *  - Requests AUDIOFOCUS_GAIN before [AudioTrack.play] so we don't fight other apps for the
 *    Sony TV speaker.
 */
class RaopAudioReceiver(private val context: Context) {
    companion object {
        private const val TAG = "RaopAudioReceiver"
        private const val DEFAULT_SAMPLE_RATE = 44100
    }

    data class Ports(val data: Int, val control: Int, val timing: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private var alacDecoder: AlacDecoder? = null
    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var numChannels: Int = 2

    private val focusHelper = AudioFocusHelper(context, object : AudioFocusHelper.Listener {
        override fun onFocusGained() = runCatching { audioTrack?.play() }.let {}
        override fun onFocusTransientLoss() = runCatching { audioTrack?.pause() }.let {}
        override fun onFocusPermanentLoss() = stop()
    })

    fun allocatePorts(): Ports {
        val d = DatagramSocket(0).also { dataSocket = it }
        val c = DatagramSocket(0).also { controlSocket = it }
        val t = DatagramSocket(0).also { timingSocket = it }
        return Ports(d.localPort, c.localPort, t.localPort)
    }

    /** Called from RTSP ANNOUNCE handler with the SDP a=fmtp:<pt> ... string. */
    fun configureFromFmtp(fmtp: String) {
        val cfg = AlacDecoder.Config.parseFmtp(fmtp) ?: return
        sampleRate = cfg.sampleRate
        numChannels = cfg.numChannels
        alacDecoder = AlacDecoder(cfg)
        Log.i(TAG, "ALAC configured: ${cfg.numChannels}ch ${cfg.bitDepth}bit ${cfg.sampleRate}Hz frame=${cfg.frameLength}")
    }

    fun startPlayback() {
        val data = dataSocket ?: return
        if (!focusHelper.request()) {
            Log.w(TAG, "audio focus denied — playback may be silent")
        }
        val channelMask = if (numChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8192)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrack = track

        val decoder = alacDecoder
        jobs += scope.launch {
            val buf = ByteArray(8192)
            // ALAC writes interleaved L/R samples — capacity must be frameLength * numChannels.
            val pcmCapacity = decoder?.let { it.maxOutputSamples() * it.config.numChannels.coerceAtLeast(1) }
                ?: 4096
            val pcm = ShortArray(pcmCapacity)
            while (isActive) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    data.receive(pkt)
                    if (pkt.length <= 12) continue
                    val payloadOffset = 12
                    val payloadLength = pkt.length - payloadOffset
                    if (decoder != null) {
                        val sampleCount = decoder.decode(buf, payloadOffset, payloadLength, pcm)
                        if (sampleCount > 0) {
                            track.write(pcm, 0, sampleCount * decoder.config.numChannels)
                        }
                    } else {
                        // Raw 16-bit BE PCM → swap to LE for AudioTrack.
                        val end = payloadOffset + (payloadLength and 1.inv())
                        var i = payloadOffset
                        while (i + 1 < end) {
                            val hi = buf[i]; buf[i] = buf[i + 1]; buf[i + 1] = hi
                            i += 2
                        }
                        track.write(buf, payloadOffset, end - payloadOffset)
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "audio recv", e)
                }
            }
        }
    }

    fun flush() {
        runCatching { audioTrack?.flush() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        alacDecoder = null
        runCatching { focusHelper.abandon() }
        runCatching { dataSocket?.close() }
        runCatching { controlSocket?.close() }
        runCatching { timingSocket?.close() }
        dataSocket = null; controlSocket = null; timingSocket = null
    }
}
