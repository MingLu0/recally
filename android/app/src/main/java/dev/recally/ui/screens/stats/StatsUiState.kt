package dev.recally.ui.screens.stats

import java.time.LocalDate

/**
 * One immutable UiState for the Stats screen (docs/android.md, "One UiState
 * per screen"). Every figure maps one-to-one onto `GET /stats`
 * (design-system.md, "Stats invents nothing"): `streak_days`, `reviews_today`,
 * `retention_30d`, `forecast[]` and `lapse_rate_by_type`, plus
 * `lapse_rate_by_guidance_version` for the section that hides until there is
 * more than one version.
 *
 * Deliberately absent: the curation-yield figure (in the response, not on the
 * artboard — issue #95, "Scoped out") and the G3 next-due timestamp (belongs
 * to the session summary).
 */
data class StatsUiState(
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val isUnauthorized: Boolean = false,
    val errorMessage: String? = null,
    val streakDays: Int = 0,
    val reviewsToday: Int = 0,
    val retention30d: Double = 0.0,
    val forecast: List<ForecastBar> = emptyList(),
    val lapseRateByType: Map<String, Double> = emptyMap(),
    val lapseRateByGuidanceVersion: List<GuidanceVersionLapseRate> = emptyList(),
) {
    /**
     * Lapse rate by guidance version renders only when there is more than one
     * version (docs/android.md, "Screens → 5. Stats"): with zero or one key
     * there is nothing to compare, and step 6b turns the section on by
     * writing data, not by touching Android code.
     */
    val showGuidanceVersionSection: Boolean
        get() = lapseRateByGuidanceVersion.size > 1
}

/**
 * One bar of the 7-day forecast chart. The chart always holds exactly seven
 * bars — today plus the next six calendar days — with days absent from
 * `forecast[]` filled as zeros (issue #95, "The forecast chart"), so the
 * weekday labels never stop lining up with the bars.
 */
data class ForecastBar(
    val date: LocalDate,
    val due: Int,
    val isToday: Boolean,
)

/** One row of the guidance-version lapse section; [version] is the numeric key. */
data class GuidanceVersionLapseRate(
    val version: Int,
    val lapseRate: Double,
)
