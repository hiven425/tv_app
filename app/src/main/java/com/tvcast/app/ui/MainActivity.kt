package com.tvcast.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvcast.app.R
import com.tvcast.app.databinding.ActivityMainBinding
import com.tvcast.app.event.CastEvent
import com.tvcast.app.event.CastEventBus
import com.tvcast.app.service.CastService
import com.tvcast.app.settings.AppSettings
import com.tvcast.app.util.NetworkUtils
import com.tvcast.app.util.QrUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = AppSettings(applicationContext)

        val ip = NetworkUtils.getLocalIpv4()
        b.ipAddress.text = ip
        b.qrImage.setImageBitmap(QrUtils.generate("http://$ip:49215/web"))

        b.settingsButton.setOnClickListener { openSettings() }

        CastService.start(this)

        observe()
    }

    override fun onResume() {
        super.onResume()
        b.deviceName.text = settings.deviceName.value.takeIf { it.isNotBlank() }
            ?: "Sony TV Cast (${NetworkUtils.macHex().takeLast(4)})"
        renderLastCrash()
    }

    private fun renderLastCrash() {
        val dir = java.io.File(filesDir, com.tvcast.app.TvCastApp.CRASH_DIR)
        val latest = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(latest.lastModified()))
        b.scanHint.text = getString(R.string.label_scan_hint) + "\n上次崩溃: $ts"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastEventBus.currentTarget.collect { target ->
                    if (target != null) launchPlayerOnce()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastEventBus.activeSender.collect { sender ->
                    if (sender != null) {
                        b.activeSender.text = getString(R.string.label_active_sender, sender)
                        b.activeSender.visibility = View.VISIBLE
                    } else {
                        b.activeSender.visibility = View.GONE
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastEventBus.nowPlaying.collect { renderNowPlaying(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastEventBus.history.collect { renderHistory(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.deviceName.collect {
                    b.deviceName.text = it.takeIf { s -> s.isNotBlank() }
                        ?: "Sony TV Cast (${NetworkUtils.macHex().takeLast(4)})"
                }
            }
        }
    }

    private fun launchPlayerOnce() {
        startActivity(
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun renderNowPlaying(meta: CastEvent.Metadata?) {
        if (meta == null) return
        val pieces = listOfNotNull(meta.title, meta.artist, meta.album).filter { it.isNotBlank() }
        if (pieces.isEmpty()) return
        b.activeSender.text = pieces.joinToString(" · ")
        b.activeSender.visibility = View.VISIBLE
        meta.coverArt?.let { art ->
            runCatching { BitmapFactory.decodeByteArray(art, 0, art.size) }.getOrNull()
                ?.let(b.qrImage::setImageBitmap)
        }
    }

    private fun renderHistory(items: List<String>) {
        b.historyList.removeAllViews()
        if (items.isEmpty()) {
            b.historyTitle.visibility = View.GONE
            return
        }
        b.historyTitle.visibility = View.VISIBLE
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 4 }
        for (item in items) {
            val tv = TextView(this).apply {
                text = "•  $item"
                textSize = 16f
                setTextColor(getColor(R.color.tv_text_primary))
                setPadding(8, 8, 8, 8)
                gravity = Gravity.START
            }
            b.historyList.addView(tv, lp)
        }
    }
}
