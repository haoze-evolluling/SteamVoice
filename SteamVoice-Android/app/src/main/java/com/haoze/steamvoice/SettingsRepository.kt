package com.haoze.steamvoice

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val VALID_BITRATES = setOf(64, 96, 128, 192)

enum class LatencyMode(val label: String, val targetMs: Int) {
    LOW("低延迟", 20), BALANCED("均衡", 40), STABLE("稳定", 80)
}

data class AudioSettings(val latency: LatencyMode = LatencyMode.BALANCED, val bitrateKbps: Int = 128)

private val Context.settingsDataStore by preferencesDataStore(name = "steamvoice_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val latency = intPreferencesKey("latency_mode")
        val bitrate = intPreferencesKey("opus_bitrate_kbps")
    }

    val settings: Flow<AudioSettings> = context.settingsDataStore.data.map { prefs: Preferences ->
        val mode = LatencyMode.entries.getOrNull(prefs[Keys.latency] ?: LatencyMode.BALANCED.ordinal)
            ?: LatencyMode.BALANCED
        val bitrate = (prefs[Keys.bitrate] ?: 128).takeIf { it in VALID_BITRATES } ?: 128
        AudioSettings(mode, bitrate)
    }

    suspend fun setLatency(mode: LatencyMode) {
        context.settingsDataStore.edit { it[Keys.latency] = mode.ordinal }
    }

    suspend fun setBitrate(kbps: Int) {
        require(kbps in VALID_BITRATES)
        context.settingsDataStore.edit { it[Keys.bitrate] = kbps }
    }
}
