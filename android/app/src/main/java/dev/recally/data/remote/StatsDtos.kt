package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /stats` response (docs/api-spec.md, "Stats"). */
@Serializable
data class StatsResponse(
    @SerialName("streak_days") val streakDays: Int,
    @SerialName("reviews_today") val reviewsToday: Int,
    /**
     * Recall on cards already learned, last 30 days. Null when no review fell in
     * the window — an absence, not 0% (docs/api-spec.md, "Stats"; issue #190).
     */
    @SerialName("retention_30d") val retention30d: Double? = null,
    /** Reviews the retention figure was computed over; 0 when it is null. */
    @SerialName("retention_30d_reviews") val retention30dReviews: Int = 0,
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
