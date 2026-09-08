package dev.recally.ui.screens.today

/**
 * The one immutable UiState for Today (docs/android.md, "One UiState per
 * screen"). Counts come from `GET /reviews/due`, the streak figures from
 * `GET /stats` (screen → endpoint map).
 *
 * Scoped out per issue #57 — no field here may carry a pending/approve or
 * needs-you count (G1), per-book progress (G2), or a next-due-in-hours figure
 * (G3); `TodayViewModelTest` enforces that reflectively.
 */
data class TodayUiState(
    val isLoading: Boolean = true,
    val dueCount: Int? = null,
    val newCount: Int? = null,
    val streakDays: Int? = null,
    val reviewsToday: Int? = null,
    val retention30d: Double? = null,
    val isOffline: Boolean = false,
    val showCheckSettingsBanner: Boolean = false,
    val errorMessage: String? = null,
) {
    /**
     * The nothing-due treatment: the fused action bar goes `line`-bordered and
     * `ink-faint` (design-system.md, "States"). G3 is scoped out, so there is
     * deliberately no "next card in N hours" — only the bare state.
     */
    val nothingDue: Boolean
        get() = !isLoading && (dueCount ?: 0) == 0 && (newCount ?: 0) == 0
}
