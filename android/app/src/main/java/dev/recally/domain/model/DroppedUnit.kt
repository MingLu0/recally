package dev.recally.domain.model

/**
 * A highlight group the Curator dropped (docs/api-spec.md, "Dropped units";
 * issue #365). Not a card: it has no status and never entered the queue —
 * the human can dismiss it, have the Writer ⇄ Critic loop write cards for
 * it, or write one by hand. Either rescue lands in the approval queue, never
 * in scheduling (hard rule 1).
 *
 * [sourceHighlights] are the content here, in export order. [truncated]
 * flags that any of them was clipped mid-word; it is a flag, never a repair
 * (hard rule 7). [reason] is the Curator's rationale for the drop.
 */
data class DroppedUnit(
    val id: Long,
    val reason: String?,
    val curatedText: String,
    val sourceHighlights: List<String>,
    val truncated: Boolean,
    val bookId: Long,
    val book: String,
    val chapter: String,
)

/**
 * The `GET /units/dropped` payload. [count] is the collection-wide
 * unresolved total — the Approve header's "N dropped" — never the list
 * length, for the same reason as [PendingCounts] (issue #132).
 */
data class DroppedUnits(
    val units: List<DroppedUnit>,
    val count: Int,
)
