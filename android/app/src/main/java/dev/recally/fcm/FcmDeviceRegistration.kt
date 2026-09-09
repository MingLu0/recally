package dev.recally.fcm

import dev.recally.domain.repository.DeviceRegistrationRepository
import dev.recally.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `POST /devices` on app start and on every token refresh (docs/android.md,
 * "Push notifications"). Goes through [DeviceRegistrationRepository] — the
 * `fcm/` package never touches the HTTP client (docs/android.md,
 * "Architecture").
 *
 * Two containment rules keep Today usable no matter what happens here:
 *
 * - Registration needs the base URL and API key, so it is a **no-op while
 *   Settings is unconfigured** — first launch must not crash or spam.
 * - Every failure — a 401, a connection failure, a token-fetch failure — is
 *   swallowed after a single attempt. There is no retry loop; the next app
 *   start is the next attempt.
 */
@Singleton
class FcmDeviceRegistration
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val registration: DeviceRegistrationRepository,
        private val tokenSource: FcmTokenSource,
    ) {
        /** Called once from `RecallyApplication.onCreate`. */
        suspend fun registerOnAppStart() {
            if (!isConfigured()) return
            val token = runCatching { tokenSource.currentToken() }.getOrNull() ?: return
            registerQuietly(token)
        }

        /** Called from the messaging service's `onNewToken`. */
        suspend fun onTokenRefreshed(fcmToken: String) {
            if (!isConfigured()) return
            registerQuietly(fcmToken)
        }

        private suspend fun isConfigured(): Boolean {
            val connection = settings.load()
            return connection.baseUrl.isNotBlank() && connection.hasApiKey
        }

        /** One attempt; the [dev.recally.domain.repository.Result] is the failure report. */
        private suspend fun registerQuietly(fcmToken: String) {
            runCatching { registration.register(fcmToken) }
        }
    }
