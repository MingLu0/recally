package dev.recally.ui.screens.today

/**
 * The one immutable UiState for Today (docs/android.md, "One UiState per
 * screen"). Counts come from `GET /reviews/due`, the streak figures and
 * [nextDueLabel] from `GET /stats` (screen → endpoint map).
 *
 * Scoped out per issue #57 — no field here may carry a pending/approve or
 * needs-you count (G1) or per-book progress (G2); `TodayViewModelTest`
 * enforces the pending-count rule reflectively.
 */
data class TodayUiState(
    val isLoading: Boolean = true,
    val dueCount: Int? = null,
    val newCount: Int? = null,
    val streakDays: Int? = null,
    val reviewsToday: Int? = null,
    val retention30d: Double? = null,
    /** "next card in 4 hours" from `next_due_at`, or null when nothing is scheduled. */
    val nextDueLabel: String? = null,
    val isOffline: Boolean = false,
    val showCheckSettingsBanner: Boolean = false,
    val errorMessage: String? = null,
) {
    /**
     * The nothing-due treatment: the fused action bar goes `line`-bordered and
     * `ink-faint` (design-system.md, "States"). When the server serves a
     * `next_due_at` the bar appends [nextDueLabel] ("Nothing due — next card
     * in 4 hours"); a null `next_due_at` keeps the bare state — never
     * "in 0 hours".
     */
    val nothingDue: Boolean
        get() = !isLoading && (dueCount ?: 0) == 0 && (newCount ?: 0) == 0
}
