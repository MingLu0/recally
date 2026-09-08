package dev.recally.domain.model

import java.time.Instant

/**
 * A card in the per-book browse list (docs/api-spec.md,
 * `GET /decks/{book_id}/cards`). Deliberately thin: the endpoint documents
 * exactly these fields and no more, so per-card FSRS `state`/`due` (G5) and
 * anything derived from them do not exist here — browse is read-only for
 * scheduling and the server stays the single FSRS authority (ADR-005).
 *
 * [suspendedUntil] is null when the card is in rotation. A past timestamp
 * (e.g. yesterday's bury, ADR-008) already reads as in rotation, matching the
 * server's `suspended_until <= now` predicate on `GET /reviews/due`.
 */
data class DeckCard(
    val id: Long,
    val type: String,
    val front: String,
    val back: String,
    val chapter: String?,
    val tags: List<String>,
    val suspendedUntil: Instant?,
) {
    /** True while the card is out of rotation (buried or suspended). */
    val isSuspended: Boolean
        get() = suspendedUntil?.isAfter(Instant.now()) == true
}
