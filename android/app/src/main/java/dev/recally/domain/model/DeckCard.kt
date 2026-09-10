package dev.recally.domain.model

import java.time.Instant

/**
 * A card in the per-book browse list (docs/api-spec.md,
 * `GET /decks/{book_id}/cards`). Every field is one the endpoint documents;
 * browse stays read-only for scheduling and the server remains the single FSRS
 * authority (ADR-005).
 *
 * [state] and [due] are that server-side FSRS position, carried so the row can
 * show it (issue #172). [due] is null for a card FSRS has never scheduled — the
 * server sends no date rather than the approval-time marker — and the client
 * renders it, never computes one (hard rule 5).
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
    val state: String,
    val due: Instant?,
) {
    /** True while the card is out of rotation (buried or suspended). */
    val isSuspended: Boolean
        get() = suspendedUntil?.isAfter(Instant.now()) == true
}
