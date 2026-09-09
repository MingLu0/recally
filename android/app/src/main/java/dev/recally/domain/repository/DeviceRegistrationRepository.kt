package dev.recally.domain.repository

/**
 * `POST /devices` registration (docs/api-spec.md, "Devices"), called on app
 * start and on every FCM token refresh (docs/android.md, "Push
 * notifications"). The interface is the seam the `fcm/` package depends on —
 * the messaging service never touches Retrofit (docs/android.md,
 * "Architecture"). Implemented by `DeviceRegistrar` in data/settings.
 */
interface DeviceRegistrationRepository {
    /**
     * Register [fcmToken]; the stored `device_id` is overwritten from every
     * response — a refreshed token is a new row and a new id.
     */
    suspend fun register(fcmToken: String): Result<Long>
}
