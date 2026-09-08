package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /health` and `GET /health/auth` response (docs/api-spec.md, "Health"). */
@Serializable
data class HealthResponse(
    val status: String,
)

/** `POST /devices` request (docs/api-spec.md, "Devices"). */
@Serializable
data class DeviceRequest(
    @SerialName("fcm_token") val fcmToken: String,
    val platform: String,
)

/** `POST /devices` response; idempotent on `fcm_token`. */
@Serializable
data class DeviceResponse(
    @SerialName("device_id") val deviceId: Long,
)
