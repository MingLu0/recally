package dev.recally.domain.model

/**
 * The `GET /stats` response (docs/api-spec.md, "Stats"). The Today screen reads
 * [streakDays], [reviewsToday] and [retention30d]; the rest is the Stats
 * screen's data, carried here because both come from the one endpoint.
 */
data class Stats(
    val streakDays: Int,
    val reviewsToday: Int,
    val retention30d: Double,
    val lapseRateByType: Map<String, Double>,
    val lapseRateByGuidanceVersion: Map<String, Double>,
    val curationYield: Double,
    val forecast: List<ForecastDay>,
)

/** One day of the review forecast; [date] is an ISO `YYYY-MM-DD` day. */
data class ForecastDay(
    val date: String,
    val due: Int,
)
