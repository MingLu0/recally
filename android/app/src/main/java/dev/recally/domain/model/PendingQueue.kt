package dev.recally.domain.model

/**
 * The `GET /cards/pending` payload: the queue itself plus the collection-wide
 * [counts] (docs/api-spec.md). One type, so neither consumer can fall back to
 * `cards.size` as a count source (issue #132).
 */
data class PendingQueue(
    val cards: List<PendingCard>,
    val counts: PendingCounts,
)
