package dev.recally.data.remote

/**
 * Runtime connection settings (docs/android.md, "Connecting to the backend"):
 * base URL and API key are entered in Settings, never baked into the build.
 * The Keystore-encrypted DataStore implementation lands with the Settings
 * screen (step 4e); the interceptors only need this read face, synchronous
 * because an OkHttp interceptor cannot suspend.
 */
interface ConnectionSettingsProvider {
    /** The configured API key, or null when Settings has never been filled in. */
    fun apiKey(): String?

    /** The configured base URL (e.g. `http://192.168.1.42:8000`), or null when unset. */
    fun baseUrl(): String?
}
