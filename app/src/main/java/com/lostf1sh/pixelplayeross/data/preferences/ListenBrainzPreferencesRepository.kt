package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.lostf1sh.pixelplayeross.data.database.ListenBrainzSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ListenBrainz is optional and local-library-only in the Telegram edition.
 * Retired remote-provider sources are deliberately disabled even if an older
 * installation left their preferences enabled.
 */
@Singleton
class ListenBrainzPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val SCROBBLE_LOCAL = booleanPreferencesKey("listenbrainz_scrobble_local")
    }

    val scrobbleLocalFlow: Flow<Boolean> = dataStore.data.map { it[Keys.SCROBBLE_LOCAL] ?: true }

    suspend fun setScrobbleLocal(enabled: Boolean) {
        dataStore.edit { it[Keys.SCROBBLE_LOCAL] = enabled }
    }

    suspend fun isSourceEnabled(source: String): Boolean {
        if (source != ListenBrainzSource.LOCAL) return false
        return dataStore.data.first()[Keys.SCROBBLE_LOCAL] ?: true
    }
}
