package dev.recally.ui.screens.settings

/**
 * What the API-key field shows when a stored key is loaded: the mask sentinel,
 * never the stored plaintext (issue #56 — the key is never exposed in the
 * UiState). Sixteen bullets, matching the Settings artboard.
 */
const val API_KEY_MASK = "••••••••••••••••"

/**
 * The push window as a display string. It mirrors the documented
 * `PUSH_WINDOW` default in `RECALLY_TIMEZONE` (docs/config.md); the value is
 * a server env var and `devices` carries no per-device preference, so the row
 * is read-only and names where the window is set (design-system.md,
 * "States").
 */
const val PUSH_WINDOW_DISPLAY = "08:00–21:00 NZST"

/**
 * The connection test against `GET /health/auth`, with the four specified
 * outcomes (design-system.md, "States"; docs/android.md, "Connecting to the
 * backend"), each carrying its own user-visible message.
 */
sealed interface ConnectionTestState {
    /** User-visible one-liner; null while idle or testing. */
    val message: String?
        get() = null

    data object Idle : ConnectionTestState

    data object Testing : ConnectionTestState

    /** 200 — the base URL and the key are both right. */
    data class Connected(
        val latencyMs: Long,
        override val message: String,
    ) : ConnectionTestState

    /** 401 — the key is wrong. */
    data class WrongKey(
        override val message: String,
    ) : ConnectionTestState

    /** No answer — the server could not be reached at all. */
    data class Unreachable(
        override val message: String,
    ) : ConnectionTestState

    /** The network security config blocked cleartext before the request left the phone. */
    data class HttpsRequired(
        override val message: String,
    ) : ConnectionTestState
}

/**
 * Everything the Settings screen renders (docs/android.md, "One UiState per
 * screen"). The stored API key is never carried as plain text: [apiKey] is
 * the text field's content — the [API_KEY_MASK] sentinel when a stored key is
 * loaded, the user's own input once they type.
 */
data class SettingsUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val hasStoredApiKey: Boolean = false,
    val isApiKeyVisible: Boolean = false,
    val isLoading: Boolean = true,
    val connectionTest: ConnectionTestState = ConnectionTestState.Idle,
    val pushWindow: String = PUSH_WINDOW_DISPLAY,
    val appVersion: String = "",
    val errorMessage: String? = null,
    /**
     * Live depth of the Room rating outbox (docs/android.md, "Offline-first
     * sync"), collected from `RatingOutbox.queuedCount()` — the same DAO
     * count the review session's queued bar reads, so the two can never
     * disagree (issue #151).
     */
    val queuedRatingsCount: Int = 0,
) {
    /** Subtitle of the queued-ratings row (Settings artboard): "All synced" once the outbox has drained. */
    val queuedRatingsSummary: String
        get() = if (queuedRatingsCount == 0) "All synced" else "Waiting to sync"
}
