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
     * Approve one card as written. The server's optional approve-with-edits
     * payload stays available for other callers (docs/api-spec.md), but the
     * app's approval queue saves edits through [saveCard] first and approves
     * the saved text with this bodyless call (issue #287).
     */
    suspend fun approveCard(cardId: Long): Result<Unit>

    /**
     * Save inline wording edits without deciding the card (issue #287):
     * `PATCH /cards/{id}` updates `front`/`back` and leaves a queued card
     * queued — status, counts and FSRS state are untouched until an explicit
     * [approveCard].
     */
    suspend fun saveCard(
        cardId: Long,
        front: String,
        back: String,
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
