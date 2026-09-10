package dev.recally.domain.repository

import dev.recally.domain.model.ApproveBatchResult
import dev.recally.domain.model.PendingQueue

/**
 * The human approval queue (docs/api-spec.md, "Approval queue").
 *
 * Requires connectivity — the queue is LLM content with no offline need, and
 * approve/reject/edit are never queued writes: ratings are the only queued
 * write (docs/android.md, "Offline-first sync"). Bulk approve goes through
 * `POST /cards/approve-batch` (issue #168); the server refuses
 * `needs_human` ids per card, so they are still opened individually
 * (hard rule 1).
 */
interface ApprovalRepository {
    /**
     * The full queue, ordered by the server by book, chapter,
     * export_position, together with the collection-wide counts — the counts
     * are part of the same payload so no consumer derives them from the list
     * length (issue #132).
     */
    suspend fun pendingCards(): Result<PendingQueue>

    /**
     * Approve one card, optionally with inline edits — edits before approval
     * belong to `POST /cards/{id}/approve`, not `PATCH` (docs/api-spec.md).
     */
    suspend fun approveCard(
        cardId: Long,
        front: String? = null,
        back: String? = null,
    ): Result<Unit>

    /**
     * Approve several clean cards in one call. Returns one result per
     * requested id, in request order. `needs_human` ids come back `ok = false`
     * — the refusal is the server's (hard rule 1), never a client-side filter.
     */
    suspend fun approveBatch(cardIds: List<Long>): Result<List<ApproveBatchResult>>

    /** Reject one card; the reason is optional (docs/android.md, "Screens → 3"). */
    suspend fun rejectCard(
        cardId: Long,
        reason: String? = null,
    ): Result<Unit>
}
