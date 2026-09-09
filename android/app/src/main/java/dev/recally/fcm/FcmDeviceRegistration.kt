package dev.recally.fcm

import dev.recally.domain.repository.DeviceRegistrationRepository
import dev.recally.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `POST /devices` on app start and on every token refresh (docs/android.md,
 * "Push notifications"). Goes through [DeviceRegistrationRepository] — the
 * `fcm/` package never touches Retrofit.
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
            val token = tokenSource.currentToken() ?: return
            registration.register(token)
        }

        /** Called from the messaging service's `onNewToken`. */
        suspend fun onTokenRefreshed(fcmToken: String) {
            registration.register(fcmToken)
        }
    }
