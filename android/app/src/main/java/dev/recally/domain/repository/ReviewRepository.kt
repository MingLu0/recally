package dev.recally.domain.repository

import dev.recally.domain.model.RateOutcome
import dev.recally.domain.model.ReviewRating

/**
 * Rating, bury and edit for the review session (docs/android.md, *Screens →
 * 2. Review session*).
 *
 * Ratings are the only queued write: the step 4i outbox persists a rating the
 * moment the user taps and flushes via `POST /reviews/rate-batch`; until that
 * lands, [rate] posts directly and a [Result.NetworkError] tells the session
 * no response came back, so it falls back to its local re-queue counter.
 * Bury and edit require connectivity and are never queued — offline they are
 * unavailable in the UI rather than deferred.
 */
interface ReviewRepository {
    /** Post one rating; the server replays it at `rating.ratedAt`. */
    suspend fun rate(rating: ReviewRating): Result<RateOutcome>

    /** `POST /cards/{id}/bury` — drops the card from the rest of the session. */
    suspend fun bury(cardId: Long): Result<Unit>

    /** `PATCH /cards/{id}` — wording fix in place; scheduling untouched (ADR-008). */
    suspend fun editCard(
        cardId: Long,
        front: String?,
        back: String?,
    ): Result<Unit>
}
