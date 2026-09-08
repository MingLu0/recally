package dev.recally.domain.repository

import dev.recally.domain.model.PendingCard

/**
 * The human approval queue (docs/api-spec.md, "Approval queue").
 *
 * Requires connectivity — the queue is LLM content with no offline need, and
 * approve/reject/edit are never queued writes: ratings are the only queued
 * write (docs/android.md, "Offline-first sync"). There is deliberately no
 * bulk approve: no batch endpoint exists (G4, docs/roadmap.md), and
 * `needs_human` cards must be opened individually either way (hard rule 1).
 */
interface ApprovalRepository {
    /** The full queue, ordered by the server by book, chapter, export_position. */
    suspend fun pendingCards(): Result<List<PendingCard>>

    /**
     * Approve one card, optionally with inline edits — edits before approval
     * belong to `POST /cards/{id}/approve`, not `PATCH` (docs/api-spec.md).
     */
    suspend fun approveCard(
        cardId: Long,
        front: String? = null,
        back: String? = null,
    ): Result<Unit>

    /** Reject one card; the reason is optional (docs/android.md, "Screens → 3"). */
    suspend fun rejectCard(
        cardId: Long,
        reason: String? = null,
    ): Result<Unit>
}
