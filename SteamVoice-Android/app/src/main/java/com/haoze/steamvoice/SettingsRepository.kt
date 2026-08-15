package com.haoze.steamvoice

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val VALID_BITRATES = setOf(64, 96, 128, 192)

data class AudioSettings(val initialBitrateKbps: Int = 128)

private val Context.settingsDataStore by preferencesDataStore(name = "steamvoice_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val bitrate = intPreferencesKey("opus_bitrate_kbps")
    }

    val settings: Flow<AudioSettings> = context.settingsDataStore.data.map { prefs: Preferences ->
        val bitrate = (prefs[Keys.bitrate] ?: 128).takeIf { it in VALID_BITRATES } ?: 128
        AudioSettings(bitrate)
    }

    suspend fun setInitialBitrate(kbps: Int) {
        require(kbps in VALID_BITRATES)
        context.settingsDataStore.edit { it[Keys.bitrate] = kbps }
    }
}
