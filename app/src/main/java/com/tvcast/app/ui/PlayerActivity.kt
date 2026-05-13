package com.tvcast.app.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tvcast.app.databinding.ActivityPlayerBinding
import com.tvcast.app.event.CastEvent
import com.tvcast.app.event.CastEventBus
import com.tvcast.app.util.AudioFocusHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Renders whatever the cast pipeline is currently asked to display:
 *  - PlayMedia → ExoPlayer (video or audio URL)
 *  - ShowPhoto → ImageView (raw image bytes pushed by AirPlay /photo)
 *
 * Driven entirely from [CastEventBus.currentTarget] so it stays in sync without intent extras
 * (photo payloads can be several MB which exceeds Binder transaction limits).
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var lastTargetSignature: String? = null
    private var focusHelper: AudioFocusHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        bindPlayer()
        observeTarget()
        observeControl()
    }

    private fun bindPlayer() {
        val p = ExoPlayer.Builder(this).build()
        b.playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    CastEventBus.updateProgress(p.currentPosition, p.duration.coerceAtLeast(0L))
                }
                if (state == Player.STATE_ENDED) {
                    CastEventBus.tryEmit(CastEvent.PlaybackEnded(CastEvent.Source.DLNA))
                }
            }
        })
        player = p

        focusHelper = AudioFocusHelper(this, object : AudioFocusHelper.Listener {
            override fun onFocusGained() { p.playWhenReady = true }
            override fun onFocusTransientLoss() { p.playWhenReady = false }
            override fun onFocusPermanentLoss() { p.playWhenReady = false }
        })

        // Periodic progress feed back to DLNA controllers polling GetPositionInfo.
        lifecycleScope.launch {
            while (isActive) {
                player?.let { CastEventBus.updateProgress(it.currentPosition, it.duration.coerceAtLeast(0L)) }
                delay(1000)
            }
        }
    }

    private fun observeTarget() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastEventBus.currentTarget.collect { target ->
                    when (target) {
                        is CastEvent.PlayMedia -> renderMedia(target)
                        is CastEvent.ShowPhoto -> renderPhoto(target)
                        is CastEvent.LaunchApp -> launchExternalApp(target)
                        null -> finish()
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun observeControl() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastEventBus.events.collect { ev ->
                    when (ev) {
                        is CastEvent.Control -> when (ev.action) {
                            CastEvent.ControlAction.PLAY -> player?.play()
                            CastEvent.ControlAction.PAUSE -> player?.pause()
                            CastEvent.ControlAction.STOP -> Unit  // handled via currentTarget=null
                            CastEvent.ControlAction.SEEK -> ev.seekTo?.let { player?.seekTo((it * 1000).toLong()) }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun renderMedia(target: CastEvent.PlayMedia) {
        val signature = "media:${target.url}"
        if (signature == lastTargetSignature) return
        lastTargetSignature = signature

        b.playerView.visibility = View.VISIBLE
        b.photoView.visibility = View.GONE
        b.photoCaption.visibility = View.GONE

        val builder = MediaItem.Builder().setUri(target.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(target.title ?: "Cast").build())
        when {
            target.mimeType?.contains("mpegurl", ignoreCase = true) == true ->
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            target.mimeType?.contains("dash", ignoreCase = true) == true ->
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        if (target.subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(target.subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                    .setMimeType(mapSubtitleMime(sub.mime, sub.url))
                    .setLanguage(sub.language ?: "und")
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build()
            })
        }
        player?.apply {
            setMediaItem(builder.build())
            if (target.startPosition > 0) seekTo((target.startPosition * 1000).toLong())
            playWhenReady = true
            prepare()
        }
        focusHelper?.request()
    }

    private fun mapSubtitleMime(declared: String?, url: String): String {
        if (declared != null) {
            val lower = declared.lowercase()
            when {
                "srt" in lower -> return MimeTypes.APPLICATION_SUBRIP
                "vtt" in lower -> return MimeTypes.TEXT_VTT
                "ssa" in lower || "ass" in lower -> return MimeTypes.TEXT_SSA
                "ttml" in lower -> return MimeTypes.APPLICATION_TTML
            }
        }
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            lowerUrl.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            lowerUrl.endsWith(".ssa") || lowerUrl.endsWith(".ass") -> MimeTypes.TEXT_SSA
            lowerUrl.endsWith(".ttml") || lowerUrl.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun renderPhoto(target: CastEvent.ShowPhoto) {
        // Prefer asset key dedup (same physical photo, different transition) before falling back
        // to hashing the raw bytes — AirPlay sends the same photo multiple times during a slideshow.
        val signature = "photo:${target.assetKey ?: target.bytes.contentHashCode()}"
        if (signature == lastTargetSignature) return
        lastTargetSignature = signature

        val bmp = runCatching { BitmapFactory.decodeByteArray(target.bytes, 0, target.bytes.size) }.getOrNull()
        if (bmp == null) {
            b.photoCaption.text = getString(com.tvcast.app.R.string.app_name) + " · decode failed"
            b.photoCaption.visibility = View.VISIBLE
            return
        }
        player?.pause()
        b.playerView.visibility = View.GONE
        b.photoView.visibility = View.VISIBLE
        b.photoView.setImageBitmap(bmp)
        applyPhotoTransition(target.transition)
        target.senderName?.let {
            b.photoCaption.text = it
            b.photoCaption.visibility = View.VISIBLE
        }
    }

    private fun applyPhotoTransition(transition: String?) {
        val view = b.photoView
        when (transition?.lowercase()) {
            "none", null -> {
                view.animate().cancel()
                view.alpha = 1f
                view.translationX = 0f
            }
            "dissolve" -> {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(400).start()
            }
            "slideleft" -> {
                view.translationX = view.width.toFloat()
                view.alpha = 1f
                view.animate().translationX(0f).setDuration(400).start()
            }
            "slideright" -> {
                view.translationX = -view.width.toFloat()
                view.alpha = 1f
                view.animate().translationX(0f).setDuration(400).start()
            }
            else -> {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(300).start()
            }
        }
    }

    private fun launchExternalApp(target: CastEvent.LaunchApp) {
        val signature = "app:${target.app}:${target.params}"
        if (signature == lastTargetSignature) return
        lastTargetSignature = signature

        val intent = when (target.app) {
            "YouTube" -> {
                val v = target.params["v"]
                val t = target.params["t"]
                val url = buildString {
                    append("https://www.youtube.com/tv")
                    if (!v.isNullOrBlank()) append("#/watch?v=$v")
                    if (!t.isNullOrBlank()) append("&t=$t")
                }
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .setPackage("com.google.android.youtube.tv")
            }
            "Netflix" -> {
                val source = target.params["source"] ?: target.params["title"] ?: ""
                android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("nflx://www.netflix.com/title/$source"))
                    .setPackage("com.netflix.ninja")
            }
            else -> {
                // Generic fallback: try opening as a URL if one is provided.
                target.params["url"]?.let {
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(it))
                }
            }
        }
        if (intent == null || packageManager.resolveActivity(intent, 0) == null) {
            b.photoCaption.text = "DIAL ${target.app}: 未安装对应应用"
            b.photoCaption.visibility = View.VISIBLE
            b.playerView.visibility = View.GONE
            b.photoView.visibility = View.GONE
            return
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onSuccess {
                // We've handed control to the external app; reset our state and dismiss the player.
                CastEventBus.tryEmit(CastEvent.SenderDisconnected(CastEvent.Source.DIAL))
                finish()
            }
            .onFailure {
                b.photoCaption.text = "DIAL ${target.app}: ${it.message}"
                b.photoCaption.visibility = View.VISIBLE
            }
    }

    override fun onDestroy() {
        runCatching { focusHelper?.abandon() }
        focusHelper = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
