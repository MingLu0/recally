package dev.recally.ui.screens.review

/**
 * UiState for the review session (docs/android.md, "One UiState per screen").
 *
 * The hard-rule surface lives here:
 * - `response_ms` is flip-to-rate, so [answer] and [ratingHints] are null
 *   until the flip — nothing that hints at the answer may be on screen before
 *   it (design-system.md, "Constraints").
 * - Progress is **cards left**, never a fixed total: Again/Hard cards are
 *   re-queued inside the session, so a denominator would jump backwards.
 * - Interval hints exist on Again and Hard only; Good/Easy projections would
 *   be the client computing scheduling state (hard rule 5, ADR-005).
 */
data class ReviewUiState(
    val isLoading: Boolean = true,
    val isUnauthorized: Boolean = false,
    val isOffline: Boolean = false,
    /** Ratings tapped while offline — the "Offline — N ratings queued" bar. */
    val queuedRatingCount: Int = 0,
    val card: ReviewCardUi? = null,
    val isFlipped: Boolean = false,
    /** The back text. Null until the flip, by construction. */
    val answer: String? = null,
    /** Null until the flip; [RatingHints.good]/[RatingHints.easy] always null. */
    val ratingHints: RatingHints? = null,
    /** Cards that left the session answered (Good/Easy, or past the last step). */
    val doneCount: Int = 0,
    /** Cards waiting to be shown again inside this session. */
    val toRepeatCount: Int = 0,
    /** Remaining presentations after the current card. Rises on a re-queue. */
    val cardsLeft: Int = 0,
    val isEditing: Boolean = false,
    /** Non-null when the session is finished — the summary sheet shows. */
    val summary: SessionSummaryUi? = null,
    val errorMessage: String? = null,
) {
    /**
     * Bury is the pre-flip escape hatch (the honest alternative to a dishonest
     * rating) and requires connectivity — it is never queued
     * (docs/android.md, "Offline-first sync").
     */
    val buryAvailable: Boolean get() = card != null && !isFlipped && !isOffline

    /** Edit fixes wording after seeing the answer; scheduling untouched (ADR-008). */
    val editAvailable: Boolean get() = card != null && isFlipped && !isOffline
}

/**
 * The card as the screen renders it. Note there is no `back` here: the answer
 * reaches the UiState only through [ReviewUiState.answer], after the flip.
 */
data class ReviewCardUi(
    val id: Long,
    val type: String,
    val front: String,
    val bookId: Long,
    val book: String,
    val chapter: String,
)

/**
 * Interval projections shown under the rating tiles (design-system.md,
 * "Rating row"). Again/Hard are derivable from `learning_steps_minutes`;
 * [good] and [easy] are always null — the server returns those intervals only
 * after the rating is submitted.
 */
data class RatingHints(
    val again: String?,
    val hard: String?,
    val good: String?,
    val easy: String?,
)

/**
 * The session summary sheet's data. Reviewed count and elapsed time are
 * local; [lapseCount] sums the `lapsed` flag over rate responses with
 * `duplicate: false`, because whether a rating is a lapse depends on state
 * the server owns (docs/api-spec.md, `POST /reviews/{id}/rate`).
 */
data class SessionSummaryUi(
    val reviewedCount: Int,
    val elapsedMs: Long,
    val goodOrEasyCount: Int,
    val hardCount: Int,
    val againCount: Int,
    val lapseCount: Int,
)
