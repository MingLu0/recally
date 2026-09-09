package dev.recally.domain.model

/**
 * The collection-wide approval-queue totals from `GET /cards/pending`
 * (docs/api-spec.md, "Approval queue"). They ignore the route's filters by
 * design (issue #132): Today's tiles and the Approve header must be right
 * before any filter exists, and deriving them from `cards.size` would be
 * wrong the moment the list is filtered or the queue is unreachable.
 */
data class PendingCounts(
    val pendingReview: Int,
    val needsHuman: Int,
) {
    /** The Approve header's "N pending" is the whole queue. */
    val total: Int
        get() = pendingReview + needsHuman
}
