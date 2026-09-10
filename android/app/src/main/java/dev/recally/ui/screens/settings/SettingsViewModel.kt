package dev.recally.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.data.sync.RatingOutbox
import dev.recally.di.IoDispatcher
import dev.recally.domain.repository.ConnectionTester
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.UnknownServiceException
import javax.inject.Inject
import javax.inject.Named

/**
 * Settings ViewModel (docs/android.md, "Architecture"): owns the one
 * [SettingsUiState] and maps the connection test's four outcomes to four
 * distinct messages (design-system.md, "States"). Instantiated at the
 * Settings `NavHost` route entry.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val connectionTester: ConnectionTester,
        private val ratingOutbox: RatingOutbox,
        @Named("appVersion") appVersion: String,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(SettingsUiState(appVersion = appVersion))
        val uiState: StateFlow<SettingsUiState> = mutableUiState.asStateFlow()

        private val exceptionHandler =
            CoroutineExceptionHandler { _, _ ->
                mutableUiState.update {
                    it.copy(
                        isLoading = false,
                        connectionTest = ConnectionTestState.Unreachable(MESSAGE_NO_ANSWER),
                        errorMessage = MESSAGE_NO_ANSWER,
                    )
                }
            }

        init {
            viewModelScope.launch(exceptionHandler) {
                val stored = withContext(ioDispatcher) { settings.load() }
                mutableUiState.update {
                    it.copy(
                        baseUrl = stored.baseUrl,
                        // Mask sentinel, never the stored plaintext (issue #56).
                        apiKey = if (stored.hasApiKey) API_KEY_MASK else "",
                        hasStoredApiKey = stored.hasApiKey,
                        isLoading = false,
                    )
                }
            }
            // The queued-ratings row is the DAO's live count, not a local
            // counter — the same flow the review session's queued bar reads
            // (#115), so Settings cannot disagree with what is stored (#151).
            viewModelScope.launch(exceptionHandler) {
                ratingOutbox.queuedCount().collect { count ->
                    mutableUiState.update { it.copy(queuedRatingsCount = count) }
                }
            }
        }

        fun onBaseUrlChange(value: String) {
            mutableUiState.update { it.copy(baseUrl = value) }
        }

        fun onApiKeyChange(value: String) {
            mutableUiState.update { it.copy(apiKey = value) }
        }

        fun onToggleApiKeyVisibility() {
            mutableUiState.update { it.copy(isApiKeyVisible = !it.isApiKeyVisible) }
        }

        /**
         * Saves the entered values first — the test exercises what would be
         * used from now on — then calls `GET /health/auth`.
         */
        fun testConnection() {
            val snapshot = mutableUiState.value
            viewModelScope.launch(exceptionHandler) {
                mutableUiState.update { it.copy(connectionTest = ConnectionTestState.Testing) }

                // The mask sentinel means "keep the stored key": save null so
                // the sentinel is never persisted as the key.
                val apiKeyToSave =
                    when {
                        snapshot.apiKey == API_KEY_MASK && snapshot.hasStoredApiKey -> null
                        snapshot.apiKey.isBlank() -> null
                        else -> snapshot.apiKey
                    }
                withContext(ioDispatcher) {
                    settings.saveConnection(snapshot.baseUrl, apiKeyToSave)
                }

                val startedAtMs = System.currentTimeMillis()
                val result = withContext(ioDispatcher) { connectionTester.test() }
                val latencyMs = System.currentTimeMillis() - startedAtMs

                mutableUiState.update {
                    it.copy(
                        hasStoredApiKey = it.hasStoredApiKey || apiKeyToSave != null,
                        connectionTest = result.toConnectionTestState(latencyMs),
                    )
                }
            }
        }

        private fun Result<Unit>.toConnectionTestState(latencyMs: Long): ConnectionTestState =
            when (this) {
                is Result.Success ->
                    ConnectionTestState.Connected(
                        latencyMs = latencyMs,
                        message = "Responded in $latencyMs ms",
                    )
                Result.Unauthorized ->
                    ConnectionTestState.WrongKey(MESSAGE_WRONG_KEY)
                is Result.NetworkError ->
                    if (cause.isCleartextBlocked()) {
                        ConnectionTestState.HttpsRequired(MESSAGE_HTTPS_REQUIRED)
                    } else {
                        ConnectionTestState.Unreachable(MESSAGE_NO_ANSWER)
                    }
                is Result.HttpError ->
                    ConnectionTestState.Unreachable(
                        "Server answered with an error (HTTP $status) — check the URL",
                    )
            }

        private companion object {
            const val MESSAGE_WRONG_KEY =
                "Couldn't authenticate — the API key is wrong. Check the key in Settings."
            const val MESSAGE_NO_ANSWER =
                "No answer from the server — check the URL, that the backend is running, " +
                    "and that the phone is on the same network."
            const val MESSAGE_HTTPS_REQUIRED =
                "HTTPS required — the network security config blocked cleartext to this " +
                    "host before the request left the phone."
        }
    }

/**
 * Cleartext blocked by the network security config surfaces as an
 * [UnknownServiceException] naming CLEARTEXT before any bytes leave the phone
 * (docs/android.md, "Connecting to the backend").
 */
internal fun Throwable.isCleartextBlocked(): Boolean =
    generateSequence(this) { it.cause }.any { cause ->
        cause is UnknownServiceException && cause.message?.contains("CLEARTEXT", ignoreCase = true) == true
    }
