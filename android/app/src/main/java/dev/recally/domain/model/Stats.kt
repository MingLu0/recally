package dev.recally.domain.model

import java.time.Instant

/**
 * The `GET /stats` response (docs/api-spec.md, "Stats"). The Today screen reads
 * [streakDays], [reviewsToday], [retention30d] and [nextDueAt]; the rest is the
 * Stats screen's data, carried here because both come from the one endpoint.
 *
 * [retention30d] is null when no review fell in the 30-day window: an
 * absence, never 0% (issue #190). [retention30dReviews] is how many reviews
 * it was computed over, so the app can qualify a small sample.
 *
 * [nextDueAt] is the earliest future due across approved, unsuspended cards —
 * the hours-away figure behind Today's nothing-due line and the session
 * summary's "Next card due in 4 hours" (issue #134). Null when nothing is
 * scheduled. It is served, never computed client-side (hard rule 5).
 */
data class Stats(
    val streakDays: Int,
    val reviewsToday: Int,
    val retention30d: Double?,
    val retention30dReviews: Int,
    val lapseRateByType: Map<String, Double>,
    val lapseRateByGuidanceVersion: Map<String, Double>,
    val curationYield: Double,
    val nextDueAt: Instant?,
    val forecast: List<ForecastDay>,
)

/** One day of the review forecast; [date] is an ISO `YYYY-MM-DD` day. */
data class ForecastDay(
    val date: String,
    val due: Int,
)
