package dev.recally.domain.repository

/**
 * Per-card reject-reason drafts for the approval queue (docs/android.md,
 * "Screens → 3"; issue #343). A draft is what the user typed into the reject
 * dialog before confirming: it is saved on every keystroke, survives process
 * death, and never reaches the server — confirming Reject passes the text
 * through the existing reject path. A draft is a convenience, so nothing here
 * throws on a storage failure: [load] answers an empty map and the writes are
 * dropped.
 */
interface RejectReasonDraftsRepository {
    /**
     * Every stored draft keyed by card id. A read failure answers an empty
     * map, never a throw — a lost convenience must not block the queue.
     */
    suspend fun load(): Map<Long, String>

    /**
     * Store [reason] for [cardId]; a blank [reason] removes the draft. Writes
     * must be applied in the order they arrive — the last keystroke wins.
     */
    suspend fun save(
        cardId: Long,
        reason: String,
    )

    /** Remove the drafts for exactly [cardIds]; every other draft stays. */
    suspend fun clear(cardIds: Set<Long>)
}
