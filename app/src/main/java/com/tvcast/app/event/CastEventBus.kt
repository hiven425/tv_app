package com.tvcast.app.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class CastEvent {
    /** A sender pushed a media URL to play. */
    data class PlayMedia(
        val source: Source,
        val url: String,
        val mimeType: String?,
        val title: String?,
        val senderName: String?,
        val startPosition: Double = 0.0,
        val subtitles: List<Subtitle> = emptyList(),
    ) : CastEvent()

    data class Subtitle(val url: String, val mime: String?, val language: String?)

    /** A sender pushed image bytes (e.g. AirPlay PUT /photo). */
    data class ShowPhoto(
        val source: Source,
        val bytes: ByteArray,
        val senderName: String?,
        val assetKey: String? = null,
        val transition: String? = null,
    ) : CastEvent() {
        override fun equals(other: Any?): Boolean = other is ShowPhoto &&
            source == other.source && senderName == other.senderName && bytes.contentEquals(other.bytes) &&
            assetKey == other.assetKey && transition == other.transition
        override fun hashCode(): Int =
            (((source.hashCode() * 31 + senderName.hashCode()) * 31 + bytes.contentHashCode()) * 31 +
                (assetKey?.hashCode() ?: 0)) * 31 + (transition?.hashCode() ?: 0)
    }

    /** Now-playing metadata pushed via AirPlay SET_PARAMETER. */
    data class Metadata(
        val source: Source,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationSeconds: Double? = null,
        val coverArt: ByteArray? = null,
    ) : CastEvent() {
        override fun equals(other: Any?): Boolean = other is Metadata &&
            source == other.source && title == other.title && artist == other.artist &&
            album == other.album && durationSeconds == other.durationSeconds &&
            ((coverArt == null && other.coverArt == null) ||
                (coverArt != null && other.coverArt != null && coverArt.contentEquals(other.coverArt)))
        override fun hashCode(): Int {
            var r = source.hashCode()
            r = r * 31 + (title?.hashCode() ?: 0)
            r = r * 31 + (artist?.hashCode() ?: 0)
            r = r * 31 + (album?.hashCode() ?: 0)
            r = r * 31 + (durationSeconds?.hashCode() ?: 0)
            r = r * 31 + (coverArt?.contentHashCode() ?: 0)
            return r
        }
    }

    /** Volume change from any source, value in [0,1]. */
    data class Volume(val source: Source, val level: Float) : CastEvent()

    data class Control(val source: Source, val action: ControlAction, val seekTo: Double? = null) : CastEvent()

    /** Player reached the end of the current track — used to trigger playlist advancement. */
    data class PlaybackEnded(val source: Source) : CastEvent()

    /** DIAL app launch request — receiver should deep-link into the named app. */
    data class LaunchApp(
        val source: Source,
        val app: String,
        val params: Map<String, String>,
        val senderHost: String?,
    ) : CastEvent()

    data class SenderConnected(val source: Source, val name: String) : CastEvent()
    data class SenderDisconnected(val source: Source) : CastEvent()
    data class StatusMessage(val text: String) : CastEvent()

    enum class Source { DLNA, AIRPLAY, CAST, DIAL, WEBUI }
    enum class ControlAction { PLAY, PAUSE, STOP, SEEK }
}

object CastEventBus {
    private val _events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    private val _activeSender = MutableStateFlow<String?>(null)
    val activeSender: StateFlow<String?> = _activeSender.asStateFlow()

    private val _nowPlaying = MutableStateFlow<CastEvent.Metadata?>(null)
    val nowPlaying: StateFlow<CastEvent.Metadata?> = _nowPlaying.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /** Latest media that the player should render. Updated by PlayMedia and ShowPhoto. */
    private val _currentTarget = MutableStateFlow<CastEvent?>(null)
    val currentTarget: StateFlow<CastEvent?> = _currentTarget.asStateFlow()

    /** Player progress, fed by PlayerActivity, read by DLNA GetPositionInfo. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    fun updateProgress(positionMs: Long, durationMs: Long) {
        _positionMs.value = positionMs.coerceAtLeast(0)
        _durationMs.value = durationMs.coerceAtLeast(0)
    }

    fun tryEmit(event: CastEvent) {
        when (event) {
            is CastEvent.SenderConnected -> {
                _activeSender.value = event.name
                _history.value = (listOf(event.name) + _history.value.filter { it != event.name }).take(5)
            }
            is CastEvent.SenderDisconnected -> {
                _activeSender.value = null
                _nowPlaying.value = null
                _currentTarget.value = null
            }
            is CastEvent.Metadata -> {
                val prev = _nowPlaying.value
                _nowPlaying.value = mergeMetadata(prev, event)
            }
            is CastEvent.PlayMedia, is CastEvent.ShowPhoto, is CastEvent.LaunchApp -> _currentTarget.value = event
            is CastEvent.Control -> if (event.action == CastEvent.ControlAction.STOP) _currentTarget.value = null
            else -> Unit
        }
        _events.tryEmit(event)
    }

    private fun mergeMetadata(prev: CastEvent.Metadata?, next: CastEvent.Metadata): CastEvent.Metadata =
        if (prev == null) next else CastEvent.Metadata(
            source = next.source,
            title = next.title ?: prev.title,
            artist = next.artist ?: prev.artist,
            album = next.album ?: prev.album,
            durationSeconds = next.durationSeconds ?: prev.durationSeconds,
            coverArt = next.coverArt ?: prev.coverArt,
        )
}
