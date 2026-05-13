package com.tvcast.app.ui

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.tvcast.app.R
import com.tvcast.app.settings.AppSettings

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(applicationContext)

        val edit = findViewById<EditText>(R.id.edit_device_name)
        edit.setText(settings.deviceName.value)
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) settings.setDeviceName(edit.text.toString())
        }

        findViewById<SettingRow>(R.id.row_dlna).bind(
            getString(R.string.settings_dlna_enabled),
            getString(R.string.settings_dlna_summary),
            settings.dlnaEnabled.value,
        ) { settings.setDlnaEnabled(it) }

        findViewById<SettingRow>(R.id.row_airplay).bind(
            getString(R.string.settings_airplay_enabled),
            getString(R.string.settings_airplay_summary),
            settings.airplayEnabled.value,
        ) { settings.setAirplayEnabled(it) }

        findViewById<SettingRow>(R.id.row_dial).bind(
            getString(R.string.settings_dial_enabled),
            getString(R.string.settings_dial_summary),
            settings.dialEnabled.value,
        ) { settings.setDialEnabled(it) }

        findViewById<SettingRow>(R.id.row_cast).bind(
            getString(R.string.settings_cast_advertise),
            getString(R.string.settings_cast_summary),
            settings.castAdvertise.value,
        ) { settings.setCastAdvertise(it) }
    }

    override fun onPause() {
        super.onPause()
        // Final save on activity pause in case the EditText still had focus.
        val edit = findViewById<EditText>(R.id.edit_device_name)
        settings.setDeviceName(edit.text.toString())
    }
}
