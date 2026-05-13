package com.tvcast.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tvcast.app.R
import com.tvcast.app.airplay.AirPlayReceiver
import com.tvcast.app.dial.DialReceiver
import com.tvcast.app.dlna.DlnaRenderer
import com.tvcast.app.event.CastEvent
import com.tvcast.app.event.CastEventBus
import com.tvcast.app.settings.AppSettings
import com.tvcast.app.ui.MainActivity
import com.tvcast.app.util.NetworkUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the DLNA + AirPlay + DIAL receivers alive while the app is
 * backgrounded. Also owns a [MediaSessionCompat] that mirrors the current cast state into the
 * system media controls, and applies AirPlay/DLNA volume requests to the actual TV speaker via
 * [AudioManager].
 */
class CastService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.tvcast.app.START"
        const val ACTION_STOP = "com.tvcast.app.STOP"
        const val ACTION_PLAY_PAUSE = "com.tvcast.app.PLAY_PAUSE"
        const val ACTION_STOP_CAST = "com.tvcast.app.STOP_CAST"
        private const val CHANNEL_ID = "tvcast.cast"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, CastService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() { fun service(): CastService = this@CastService }
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }

    private lateinit var settings: AppSettings
    private lateinit var dlna: DlnaRenderer
    private lateinit var airplay: AirPlayReceiver
    private lateinit var dial: DialReceiver
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var started = false
    @Volatile private var dlnaRunning = false
    @Volatile private var airplayRunning = false
    @Volatile private var dialRunning = false
    @Volatile private var lastKnownIp: String = ""

    val deviceName: String
        get() = settings.deviceName.value.takeIf { it.isNotBlank() }
            ?: "Sony TV Cast (${NetworkUtils.macHex().takeLast(4)})"

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        createNotificationChannel()
        dlna = DlnaRenderer(
            context = this,
            friendlyName = { deviceName },
            dialApplicationUrl = {
                if (settings.dialEnabled.value)
                    "http://${NetworkUtils.getLocalIpv4()}:${DialReceiver.DIAL_PORT}/apps/"
                else null
            },
        )
        airplay = AirPlayReceiver(this) { deviceName }
        dial = DialReceiver(this) { deviceName }
        mediaSession = MediaSessionCompat(this, "TvCast").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = CastEventBus.tryEmit(
                    CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.PLAY)
                )
                override fun onPause() = CastEventBus.tryEmit(
                    CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.PAUSE)
                )
                override fun onStop() = CastEventBus.tryEmit(
                    CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.STOP)
                )
            })
        }
        observeEvents()
        observeSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> CastEventBus.tryEmit(
                CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.PAUSE)
            )
            ACTION_STOP_CAST -> CastEventBus.tryEmit(
                CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.STOP)
            )
            else -> bootstrap()
        }
        return START_STICKY
    }

    private fun bootstrap() {
        if (started) return
        started = true

        startForegroundCompat(NowPlayingSnapshot.empty())

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvCast::Service").apply {
            setReferenceCounted(false)
            acquire(/* 12h */ 12 * 60 * 60 * 1000L)
        }
        lastKnownIp = NetworkUtils.getLocalIpv4()
        registerNetworkCallback()
        registerScreenReceiver()
        applyReceiverFlags(
            dlnaOn = settings.dlnaEnabled.value,
            airplayOn = settings.airplayEnabled.value,
            dialOn = settings.dialEnabled.value,
        )
    }

    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF ->
                        CastEventBus.tryEmit(CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.PAUSE))
                    Intent.ACTION_SCREEN_ON -> {
                        // Only resume if something was actively being cast — currentTarget != null.
                        if (CastEventBus.currentTarget.value is CastEvent.PlayMedia) {
                            CastEventBus.tryEmit(CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.PLAY))
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lifecycleScope.launch { handleNetworkChanged() }
            }
            override fun onLost(network: Network) {
                lifecycleScope.launch { handleNetworkChanged() }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                lifecycleScope.launch { handleNetworkChanged() }
            }
        }
        networkCallback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    private suspend fun handleNetworkChanged() {
        // Debounce: ConnectivityManager can fire several callbacks back-to-back during a Wi-Fi reconnect.
        kotlinx.coroutines.delay(750)
        val newIp = NetworkUtils.getLocalIpv4()
        if (newIp == lastKnownIp) return
        android.util.Log.i("CastService", "Network changed: $lastKnownIp → $newIp; bouncing receivers")
        lastKnownIp = newIp
        // Stop everything and start back up with the new IP baked into mDNS / SSDP advertisements.
        runCatching { dial.stop() }; dialRunning = false
        runCatching { airplay.stop() }; airplayRunning = false
        runCatching { dlna.stop() }; dlnaRunning = false
        applyReceiverFlags(
            dlnaOn = settings.dlnaEnabled.value,
            airplayOn = settings.airplayEnabled.value,
            dialOn = settings.dialEnabled.value,
        )
    }

    private fun applyReceiverFlags(dlnaOn: Boolean, airplayOn: Boolean, dialOn: Boolean) {
        if (dlnaOn && !dlnaRunning) { dlna.start(); dlnaRunning = true }
        else if (!dlnaOn && dlnaRunning) { runCatching { dlna.stop() }; dlnaRunning = false }
        if (airplayOn && !airplayRunning) { airplay.start(); airplayRunning = true }
        else if (!airplayOn && airplayRunning) { runCatching { airplay.stop() }; airplayRunning = false }
        if (dialOn && !dialRunning) { dial.start(); dialRunning = true }
        else if (!dialOn && dialRunning) { runCatching { dial.stop() }; dialRunning = false }
    }

    private fun shutdown() {
        if (!started) return
        started = false
        networkCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        runCatching { dial.stop() }; dialRunning = false
        runCatching { airplay.stop() }; airplayRunning = false
        runCatching { dlna.stop() }; dlnaRunning = false
        runCatching { mediaSession?.release() }
        mediaSession = null
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            CastEventBus.events.collectLatest { ev ->
                when (ev) {
                    is CastEvent.Volume -> applyVolume(ev.level)
                    is CastEvent.Control -> updateSessionState(ev.action)
                    is CastEvent.PlaybackEnded -> advancePlaylist()
                    else -> Unit
                }
            }
        }
        lifecycleScope.launch {
            CastEventBus.nowPlaying.collectLatest { meta ->
                updateSessionMetadata(meta)
                refreshNotification(NowPlayingSnapshot.from(meta, CastEventBus.activeSender.value))
            }
        }
        lifecycleScope.launch {
            CastEventBus.activeSender.collectLatest { sender ->
                refreshNotification(NowPlayingSnapshot.from(CastEventBus.nowPlaying.value, sender))
            }
        }
    }

    private fun advancePlaylist() {
        val next = dlna.rendererState.consumeNext() ?: return
        val (uri, metaXml) = next
        val meta = com.tvcast.app.dlna.MetadataParser.parse(metaXml)
        dlna.rendererState.setCurrent(uri, meta)
        CastEventBus.tryEmit(
            CastEvent.PlayMedia(
                source = CastEvent.Source.DLNA,
                url = uri,
                mimeType = meta.mime,
                title = meta.title,
                senderName = CastEventBus.activeSender.value,
                subtitles = meta.subtitles.map { CastEvent.Subtitle(it.url, it.mime, it.language) },
            )
        )
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            settings.dlnaEnabled.collectLatest { applyReceiverFlags(it, settings.airplayEnabled.value, settings.dialEnabled.value) }
        }
        lifecycleScope.launch {
            settings.airplayEnabled.collectLatest { applyReceiverFlags(settings.dlnaEnabled.value, it, settings.dialEnabled.value) }
        }
        lifecycleScope.launch {
            settings.dialEnabled.collectLatest { applyReceiverFlags(settings.dlnaEnabled.value, settings.airplayEnabled.value, it) }
        }
    }

    private fun applyVolume(level: Float) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (level.coerceIn(0f, 1f) * max).toInt()
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    private fun updateSessionState(action: CastEvent.ControlAction) {
        val state = when (action) {
            CastEvent.ControlAction.PLAY -> PlaybackStateCompat.STATE_PLAYING
            CastEvent.ControlAction.PAUSE -> PlaybackStateCompat.STATE_PAUSED
            CastEvent.ControlAction.STOP -> PlaybackStateCompat.STATE_STOPPED
            CastEvent.ControlAction.SEEK -> PlaybackStateCompat.STATE_BUFFERING
        }
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun updateSessionMetadata(meta: CastEvent.Metadata?) {
        if (meta == null) return
        val mb = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, meta.title ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, meta.artist ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta.album ?: "")
        meta.coverArt?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                .getOrNull()
                ?.let { mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        }
        meta.durationSeconds?.let { mb.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (it * 1000).toLong()) }
        mediaSession?.setMetadata(mb.build())
    }

    private fun refreshNotification(snap: NowPlayingSnapshot) {
        if (!started) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(snap))
    }

    private fun startForegroundCompat(snap: NowPlayingSnapshot) {
        val notif = buildNotification(snap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(snap: NowPlayingSnapshot): Notification {
        val ip = NetworkUtils.getLocalIpv4()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CastService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CastService::class.java).setAction(ACTION_STOP_CAST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (snap.title != null) {
            builder.setContentTitle(snap.title)
                .setContentText(listOfNotNull(snap.artist, snap.sender).joinToString(" · "))
            snap.coverBytes?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    .getOrNull()?.let(builder::setLargeIcon)
            }
            builder.addAction(
                android.R.drawable.ic_media_pause, getString(R.string.action_pause), pauseIntent
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), stopIntent
            )
            mediaSession?.sessionToken?.let { token ->
                builder.setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0, 1)
                )
            }
        } else {
            builder.setContentTitle(getString(R.string.notif_running))
                .setContentText(getString(R.string.notif_running_desc, deviceName, ip))
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_desc) }
        nm.createNotificationChannel(channel)
    }

    private data class NowPlayingSnapshot(
        val title: String?, val artist: String?, val sender: String?, val coverBytes: ByteArray?,
    ) {
        companion object {
            fun empty() = NowPlayingSnapshot(null, null, null, null)
            fun from(meta: CastEvent.Metadata?, sender: String?) = NowPlayingSnapshot(
                title = meta?.title,
                artist = meta?.artist,
                sender = sender,
                coverBytes = meta?.coverArt,
            )
        }
    }
}
