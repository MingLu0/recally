package dev.recally.domain.repository

/**
 * The connection settings the Settings screen owns (docs/android.md,
 * "Connecting to the backend"). The stored API key is never read back as
 * plain text for display — [hasApiKey] is all the UI needs.
 */
data class StoredConnection(
    val baseUrl: String,
    val hasApiKey: Boolean,
    val deviceId: Long?,
)

/**
 * Read/write face of the Keystore-encrypted DataStore (data/settings). Base
 * URL and API key are entered once in Settings; nothing is baked into the
 * build.
 */
interface SettingsRepository {
    /** Current stored values; the API key is reported only as [StoredConnection.hasApiKey]. */
    suspend fun load(): StoredConnection

    /**
     * Persist the connection settings. A null [apiKey] keeps the stored key —
     * the Settings screen passes null when the masked sentinel is left
     * untouched, so the mask is never saved as the key.
     */
    suspend fun saveConnection(
        baseUrl: String,
        apiKey: String?,
    )

    /** Overwrite the stored `device_id` — every `POST /devices` response replaces it. */
    suspend fun saveDeviceId(deviceId: Long)
}

/**
 * The Settings connection test against `GET /health/auth`
 * (docs/api-spec.md, "Health"). Separate from [SettingsRepository] so the
 * store never depends on the Retrofit client it configures.
 *
 * A success carries the backend's reported version, or null from a backend too
 * old to report one — which is itself the answer the human needs when the app
 * is failing to decode that server's payloads (issue #195).
 */
interface ConnectionTester {
    suspend fun test(): Result<String?>
}
