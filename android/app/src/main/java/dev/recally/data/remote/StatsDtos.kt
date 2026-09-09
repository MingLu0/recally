package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /stats` response (docs/api-spec.md, "Stats"). */
@Serializable
data class StatsResponse(
    @SerialName("streak_days") val streakDays: Int,
    @SerialName("reviews_today") val reviewsToday: Int,
    @SerialName("retention_30d") val retention30d: Double,
    @SerialName("lapse_rate_by_type") val lapseRateByType: Map<String, Double>,
    @SerialName("lapse_rate_by_guidance_version") val lapseRateByGuidanceVersion: Map<String, Double>,
    @SerialName("curation_yield") val curationYield: Double,
    /** Earliest future due across approved, unsuspended cards; null when nothing is scheduled. */
    @SerialName("next_due_at") val nextDueAt: String?,
    val forecast: List<ForecastDayDto>,
)

/** One day of the review forecast. */
@Serializable
data class ForecastDayDto(
    val date: String,
    val due: Int,
)
