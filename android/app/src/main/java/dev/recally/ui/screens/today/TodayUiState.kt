package dev.recally.ui.screens.today

import dev.recally.domain.model.Deck

/**
 * The one immutable UiState for Today (docs/android.md, "One UiState per
 * screen"). Counts come from `GET /reviews/due`, the streak figures and
 * [nextDueLabel] from `GET /stats` (screen → endpoint map), and the
 * pending-queue buckets from the collection-wide `counts` on
 * `GET /cards/pending` (G1, issue #132) — never from a list length. They
 * stay null when the queue is unreachable: Today renders offline and the
 * queue requires connectivity.
 *
 * [books] feeds the "Your books" rail (G2, issue #133): `Deck.progress` is
 * the server's figure and is rendered unmodified — never recomputed
 * client-side from `total`/`due`. Decks are remote-only, so an unreachable
 * `GET /decks` leaves the rail empty while the rest of Today renders.
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
    val pendingReviewCount: Int? = null,
    val needsHumanCount: Int? = null,
    val books: List<Deck> = emptyList(),
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
