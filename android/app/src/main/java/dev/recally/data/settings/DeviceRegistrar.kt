package dev.recally.data.settings

import dev.recally.data.remote.DeviceRequest
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.apiCall
import dev.recally.di.IoDispatcher
import dev.recally.domain.repository.DeviceRegistrationRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `POST /devices` registration (docs/api-spec.md, "Devices"). Called on app
 * start and on every FCM token refresh (docs/android.md, "Push
 * notifications"); the FCM messaging service that supplies the token lands
 * with the push work and calls [register].
 *
 * The stored `device_id` is overwritten from **every** response, including
 * after a refresh — a refreshed `fcm_token` is a new row and a new id, and
 * queued ratings must name the phone's current id (`review_logs.device_id`).
 */
class DeviceRegistrar
    @Inject
    constructor(
        private val api: RecallyApi,
        private val settings: SettingsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : DeviceRegistrationRepository {
        override suspend fun register(fcmToken: String): Result<Long> =
            withContext(ioDispatcher) {
                when (
                    val result =
                        apiCall {
                            api.registerDevice(DeviceRequest(fcmToken = fcmToken, platform = PLATFORM))
                        }
                ) {
                    is Result.Success -> {
                        settings.saveDeviceId(result.data.deviceId)
                        Result.Success(result.data.deviceId)
                    }
                    Result.Unauthorized -> Result.Unauthorized
                    is Result.HttpError -> result
                    is Result.NetworkError -> result
                    is Result.UnexpectedError -> result
                }
            }

        private companion object {
            const val PLATFORM = "android"
        }
    }
