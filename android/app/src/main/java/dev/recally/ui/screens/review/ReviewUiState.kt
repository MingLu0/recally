package dev.recally.ui.screens.review

/**
 * UiState for the review session (docs/android.md, "One UiState per screen").
 */
data class ReviewUiState(
    val isLoading: Boolean = true,
    val isUnauthorized: Boolean = false,
    val isOffline: Boolean = false,
    val queuedRatingCount: Int = 0,
    val card: ReviewCardUi? = null,
    val isFlipped: Boolean = false,
    val answer: String? = null,
    val ratingHints: RatingHints? = null,
    val doneCount: Int = 0,
    val toRepeatCount: Int = 0,
    val cardsLeft: Int = 0,
    val totalCount: Int = 0,
    val isEditing: Boolean = false,
    val summary: SessionSummaryUi? = null,
    val errorMessage: String? = null,
) {
    val buryAvailable: Boolean get() = card != null
    val editAvailable: Boolean get() = card != null
}

/** The card as the screen renders it. */
data class ReviewCardUi(
    val id: Long,
    val type: String,
    val front: String,
    val bookId: Long,
    val book: String,
    val chapter: String,
)

/** Interval projections shown under the rating tiles. */
data class RatingHints(
    val again: String?,
    val hard: String?,
    val good: String?,
    val easy: String?,
)

/** The session summary sheet's data; counts and elapsed time are local. */
data class SessionSummaryUi(
    val reviewedCount: Int,
    val elapsedMs: Long,
    val goodOrEasyCount: Int,
    val hardCount: Int,
    val againCount: Int,
    val lapseCount: Int,
)
