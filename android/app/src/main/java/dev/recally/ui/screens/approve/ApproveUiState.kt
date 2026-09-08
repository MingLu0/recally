package dev.recally.ui.screens.approve

import dev.recally.domain.model.PendingCard

/**
 * The "needs you" filter chip (docs/android.md, "Screens → 3. Approval
 * queue"): `needs_human` cards are the ones the Writer ⇄ Critic loop could
 * not clear, so they get their own filter rather than folding into the queue.
 */
enum class QueueFilter {
    ALL,
    NEEDS_YOU,
}

/**
 * A run of cards from one book chapter. `GET /cards/pending` returns a flat
 * list ordered by book, chapter, then `export_position`; the grouping is
 * built client-side (design-system.md, "Chapter group header") and preserves
 * that order exactly.
 */
data class ChapterGroup(
    val bookId: Long,
    val book: String,
    val chapter: String,
    val cards: List<PendingCard>,
)

/**
 * Groups the server's flat, ordered list into runs of equal (book, chapter).
 * Run-based, not a re-sorting groupBy: the server's order is the queue's
 * order, and an interleaved repeat of a chapter stays in place as its own
 * group.
 */
fun groupIntoChapters(cards: List<PendingCard>): List<ChapterGroup> {
    val groups = mutableListOf<ChapterGroup>()
    for (card in cards) {
        val last = groups.lastOrNull()
        if (last != null && last.bookId == card.bookId && last.chapter == card.chapter) {
            groups[groups.lastIndex] = last.copy(cards = last.cards + card)
        } else {
            groups += ChapterGroup(bookId = card.bookId, book = card.book, chapter = card.chapter, cards = listOf(card))
        }
    }
    return groups
}

/**
 * Everything the approval queue renders (docs/android.md, "One UiState per
 * screen").
 *
 * Scoped out by issue #59, so deliberately absent: a header count (G1 —
 * `GET /cards/pending` returns no counts) and any multi-card approve
 * affordance (G4 — no batch endpoint exists, and `needs_human` cards are
 * excluded from any future bulk path by hard rule 1).
 *
 * [truncated] source highlights are a flag, never a repair (hard rule 7):
 * nothing here offers to reconstruct clipped text.
 */
data class ApproveUiState(
    val groups: List<ChapterGroup> = emptyList(),
    val filter: QueueFilter = QueueFilter.ALL,
    val isLoading: Boolean = true,
    val isOffline: Boolean = false,
    val isUnauthorized: Boolean = false,
    val expandedHighlightCardIds: Set<Long> = emptySet(),
    val editingCardId: Long? = null,
    val busyCardId: Long? = null,
    val errorMessage: String? = null,
) {
    /** The groups the current filter shows; NEEDS_YOU keeps only `needs_human` cards. */
    val visibleGroups: List<ChapterGroup>
        get() =
            when (filter) {
                QueueFilter.ALL -> groups
                QueueFilter.NEEDS_YOU ->
                    groups.mapNotNull { group ->
                        val kept = group.cards.filter(PendingCard::isNeedsHuman)
                        if (kept.isEmpty()) null else group.copy(cards = kept)
                    }
            }

    /** Source highlights are collapsed by default (design-system.md, "Source-highlight disclosure"). */
    fun isHighlightsExpanded(cardId: Long): Boolean = cardId in expandedHighlightCardIds

    /** Queue-drained is its own state: centred success check, "Queue clear" (design-system.md, "States"). */
    val isQueueClear: Boolean
        get() = !isLoading && !isUnauthorized && groups.isEmpty()
}
