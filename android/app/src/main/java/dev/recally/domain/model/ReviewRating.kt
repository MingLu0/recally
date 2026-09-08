package dev.recally.domain.model

import java.time.Instant

/**
 * A single rating event (docs/api-spec.md, POST /reviews/{card_id}/rate).
 *
 * [rating] is 1=Again, 2=Hard, 3=Good, 4=Easy. [ratedAt] is the client
 * timestamp the server replays at; [responseMs] is the flip-to-rate duration
 * captured on every rating (docs/android.md, "Offline-first sync"). The step
 * 4i outbox persists these until the server acknowledges them.
 */
data class ReviewRating(
    val cardId: Long,
    val rating: Int,
    val responseMs: Long,
    val ratedAt: Instant,
    val deviceId: Long?,
)
