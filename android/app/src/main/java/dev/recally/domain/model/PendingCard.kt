package dev.recally.domain.model

/**
 * A card in the human approval queue (docs/api-spec.md, `GET /cards/pending`).
 *
 * Distinct from [Card]: nothing here carries FSRS state, because nothing
 * enters scheduling before human approval (AGENTS.md hard rule 1).
 * [truncated] flags that a source highlight was clipped mid-word; it is a
 * flag, never a repair — the lost text exists nowhere (hard rule 7).
 * [statusReason] carries the Critic's critique when there is one.
 */
data class PendingCard(
    val id: Long,
    val status: String,
    val type: String,
    val front: String,
    val back: String,
    val statusReason: String?,
    val sourceHighlights: List<String>,
    val truncated: Boolean,
    val bookId: Long,
    val book: String,
    val chapter: String,
) {
    /** The Writer ⇄ Critic loop could not clear this card; it gets the "needs you" badge. */
    val isNeedsHuman: Boolean
        get() = status == STATUS_NEEDS_HUMAN

    val isCloze: Boolean
        get() = type == TYPE_CLOZE

    companion object {
        const val STATUS_PENDING_REVIEW = "pending_review"
        const val STATUS_NEEDS_HUMAN = "needs_human"
        const val TYPE_QA = "qa"
        const val TYPE_CLOZE = "cloze"
    }
}
