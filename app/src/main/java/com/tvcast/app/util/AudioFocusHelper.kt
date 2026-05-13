package com.tvcast.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Thin wrapper over [AudioManager.requestAudioFocus] / [AudioManager.abandonAudioFocus] that
 * handles the API 26+ / older split. Callers pass a single [Listener] which receives the
 * standard FOCUS_* events.
 */
class AudioFocusHelper(context: Context, private val listener: Listener) {

    interface Listener {
        fun onFocusGained()
        /** Called for AUDIOFOCUS_LOSS_TRANSIENT — pause but expect a quick return. */
        fun onFocusTransientLoss()
        /** Called for AUDIOFOCUS_LOSS — abandon playback entirely. */
        fun onFocusPermanentLoss()
        /** Called for AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK — lower volume but keep playing. */
        fun onFocusDuck() = onFocusTransientLoss()
    }

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> listener.onFocusGained()
            AudioManager.AUDIOFOCUS_LOSS -> listener.onFocusPermanentLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listener.onFocusTransientLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener.onFocusDuck()
        }
    }

    /** Request music-stream audio focus. Returns true if granted immediately. */
    fun request(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setWillPauseWhenDucked(true)
                .build()
            request = req
            return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        @Suppress("DEPRECATION")
        val result = audioManager.requestAudioFocus(
            focusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        )
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        request = null
    }
}
