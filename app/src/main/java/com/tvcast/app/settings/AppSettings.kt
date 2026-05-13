package com.tvcast.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tvcast")

/**
 * Reactive wrapper around DataStore<Preferences>. Each setting is exposed as a [StateFlow] so the
 * UI and [com.tvcast.app.service.CastService] can react synchronously to changes.
 *
 * On first construction we block briefly to read the initial values — the file is tiny so this
 * remains imperceptible, and it lets us hand callers a non-suspending StateFlow contract.
 */
class AppSettings(context: Context) {

    private val ds = context.applicationContext.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val keyDeviceName = stringPreferencesKey("device_name")
    private val keyDlnaEnabled = booleanPreferencesKey("dlna_enabled")
    private val keyAirplayEnabled = booleanPreferencesKey("airplay_enabled")
    private val keyDialEnabled = booleanPreferencesKey("dial_enabled")
    private val keyCastAdvertise = booleanPreferencesKey("cast_advertise")

    private val _deviceName: MutableStateFlow<String>
    private val _dlnaEnabled = MutableStateFlow(true)
    private val _airplayEnabled = MutableStateFlow(true)
    private val _dialEnabled = MutableStateFlow(true)
    private val _castAdvertise = MutableStateFlow(true)

    val deviceName: StateFlow<String> get() = _deviceName.asStateFlow()
    val dlnaEnabled: StateFlow<Boolean> get() = _dlnaEnabled.asStateFlow()
    val airplayEnabled: StateFlow<Boolean> get() = _airplayEnabled.asStateFlow()
    val dialEnabled: StateFlow<Boolean> get() = _dialEnabled.asStateFlow()
    val castAdvertise: StateFlow<Boolean> get() = _castAdvertise.asStateFlow()

    init {
        val initial = runBlocking { ds.data.first() }
        _deviceName = MutableStateFlow(initial[keyDeviceName].orEmpty())
        _dlnaEnabled.value = initial[keyDlnaEnabled] ?: true
        _airplayEnabled.value = initial[keyAirplayEnabled] ?: true
        _dialEnabled.value = initial[keyDialEnabled] ?: true
        _castAdvertise.value = initial[keyCastAdvertise] ?: true

        // Keep StateFlows in sync with any future external updates (e.g. SettingsActivity edits).
        scope.launch {
            ds.data.map { it[keyDeviceName].orEmpty() }.collect { _deviceName.value = it }
        }
        scope.launch {
            ds.data.map { it[keyDlnaEnabled] ?: true }.collect { _dlnaEnabled.value = it }
        }
        scope.launch {
            ds.data.map { it[keyAirplayEnabled] ?: true }.collect { _airplayEnabled.value = it }
        }
        scope.launch {
            ds.data.map { it[keyDialEnabled] ?: true }.collect { _dialEnabled.value = it }
        }
        scope.launch {
            ds.data.map { it[keyCastAdvertise] ?: true }.collect { _castAdvertise.value = it }
        }
    }

    fun setDeviceName(value: String) = scope.launch {
        ds.edit { it[keyDeviceName] = value }
    }

    fun setDlnaEnabled(value: Boolean) = scope.launch {
        ds.edit { it[keyDlnaEnabled] = value }
    }

    fun setAirplayEnabled(value: Boolean) = scope.launch {
        ds.edit { it[keyAirplayEnabled] = value }
    }

    fun setDialEnabled(value: Boolean) = scope.launch {
        ds.edit { it[keyDialEnabled] = value }
    }

    fun setCastAdvertise(value: Boolean) = scope.launch {
        ds.edit { it[keyCastAdvertise] = value }
    }
}
