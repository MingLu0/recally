package dev.recally.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.recally.data.remote.ConnectionSettingsProvider
import dev.recally.di.IoDispatcher
import dev.recally.domain.repository.SettingsRepository
import dev.recally.domain.repository.StoredConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Keystore-encrypted DataStore behind Settings (docs/android.md,
 * "Connecting to the backend"). Every value is encrypted with the
 * AndroidKeyStore-backed [SettingsCipher] before it touches disk; the file at
 * rest never holds plaintext.
 *
 * Two read faces:
 *
 * - [load]/[saveConnection] — the suspend face the Settings ViewModel uses.
 * - [apiKey]/[baseUrl] — the synchronous [ConnectionSettingsProvider] face
 *   the OkHttp interceptors use (an interceptor cannot suspend). It reads an
 *   in-memory decrypted cache, warmed by [load] at app start and refreshed by
 *   every save.
 */
@Singleton
class SettingsStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val cipher: SettingsCipher,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ConnectionSettingsProvider,
        SettingsRepository {
        @Volatile
        private var cachedBaseUrl: String? = null

        @Volatile
        private var cachedApiKey: String? = null

        @Volatile
        private var cachedDeviceId: Long? = null

        @Volatile
        private var loaded = false

        override fun apiKey(): String? = cachedApiKey

        override fun baseUrl(): String? = cachedBaseUrl

        /** The last `device_id` returned by `POST /devices`, or null before any registration. */
        fun deviceId(): Long? = cachedDeviceId

        /** Reads and decrypts everything into the synchronous cache. */
        override suspend fun load(): StoredConnection =
            withContext(ioDispatcher) {
                val preferences = dataStore.data.first()
                cachedBaseUrl = preferences[KEY_BASE_URL]?.let(cipher::decrypt)
                cachedApiKey = preferences[KEY_API_KEY]?.let(cipher::decrypt)
                cachedDeviceId = preferences[KEY_DEVICE_ID]
                loaded = true
                StoredConnection(
                    baseUrl = cachedBaseUrl.orEmpty(),
                    hasApiKey = cachedApiKey != null,
                    deviceId = cachedDeviceId,
                )
            }

        override suspend fun saveConnection(
            baseUrl: String,
            apiKey: String?,
        ) {
            withContext(ioDispatcher) {
                if (!loaded) load()
                val trimmedBaseUrl = baseUrl.trim()
                dataStore.edit { preferences ->
                    preferences[KEY_BASE_URL] = cipher.encrypt(trimmedBaseUrl)
                    if (apiKey != null) {
                        preferences[KEY_API_KEY] = cipher.encrypt(apiKey)
                    }
                }
                cachedBaseUrl = trimmedBaseUrl
                if (apiKey != null) {
                    cachedApiKey = apiKey
                }
            }
        }

        /** Overwrites the stored id — every `POST /devices` response replaces it. */
        override suspend fun saveDeviceId(deviceId: Long) {
            withContext(ioDispatcher) {
                dataStore.edit { preferences -> preferences[KEY_DEVICE_ID] = deviceId }
                cachedDeviceId = deviceId
            }
        }

        private companion object {
            val KEY_BASE_URL = stringPreferencesKey("base_url")
            val KEY_API_KEY = stringPreferencesKey("api_key")
            val KEY_DEVICE_ID = longPreferencesKey("device_id")
        }
    }
