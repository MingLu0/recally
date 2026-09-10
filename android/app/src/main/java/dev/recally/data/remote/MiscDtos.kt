package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /health` and `GET /health/auth` response (docs/api-spec.md, "Health").
 *
 * `version` is the backend build the probe answered from, and only the
 * authenticated twin sends it — so it is nullable here rather than required.
 * That is deliberate: a backend too old to report a version is exactly the
 * skew this field exists to reveal, and a required field would fail the
 * connection test with the parse error it is meant to explain (issue #195).
 */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String? = null,
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
