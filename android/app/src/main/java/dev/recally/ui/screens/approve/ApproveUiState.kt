package dev.recally.ui.screens.approve

import dev.recally.domain.model.DroppedUnit
import dev.recally.domain.model.PendingCard

/**
 * The Approve filter chips (docs/android.md, "Screens → 3. Approval queue"),
 * rendered in this order. `needs_human` cards are the ones the Writer ⇄
 * Critic loop could not clear, so they get their own filter rather than
 * folding into the queue. DROPPED is not a card filter at all: it shows the
 * Curator's drops, a separate list (issue #365).
 */
enum class QueueFilter {
    ALL,
    NEEDS_YOU,
    DROPPED,
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

/** A run of dropped units from one book chapter — the same run-based grouping as [ChapterGroup]. */
data class DroppedChapterGroup(
    val bookId: Long,
    val book: String,
    val chapter: String,
    val units: List<DroppedUnit>,
)

/** Groups `GET /units/dropped`'s ordered list into runs, preserving the server order. */
fun groupDropsIntoChapters(units: List<DroppedUnit>): List<DroppedChapterGroup> {
    val groups = mutableListOf<DroppedChapterGroup>()
    for (unit in units) {
        val last = groups.lastOrNull()
        if (last != null && last.bookId == unit.bookId && last.chapter == unit.chapter) {
            groups[groups.lastIndex] = last.copy(units = last.units + unit)
        } else {
            groups += DroppedChapterGroup(bookId = unit.bookId, book = unit.book, chapter = unit.chapter, units = listOf(unit))
        }
    }
    return groups
}

/**
 * The open "Write card" editor on one dropped unit (issue #365). Held in the
 * UiState, not in Compose, so a failed submit keeps the typed text and shows
 * the server's 422 [error] inline.
 */
data class ManualCardDraft(
    val unitId: Long,
    val type: String = PendingCard.TYPE_QA,
    val front: String = "",
    val back: String = "",
    val error: String? = null,
) {
    /**
     * Add is enabled only for a plausible card: a Q&A needs both sides, a
     * cloze a `{{c1::…}}` deletion in its front. Nothing parses the cloze
     * beyond that — the server validates.
     */
    val canSubmit: Boolean
        get() =
            when (type) {
                PendingCard.TYPE_CLOZE -> hasClozeDeletion(front)
                else -> front.isNotBlank() && back.isNotBlank()
            }

    /** The cloze editor's hint shows while the front lacks a deletion. */
    val showsClozeHint: Boolean
        get() = type == PendingCard.TYPE_CLOZE

    private companion object {
        const val CLOZE_OPEN = "{{c1::"
        const val CLOZE_CLOSE = "}}"

        /** `{{c1::` followed, later, by a non-empty answer and `}}`. */
        fun hasClozeDeletion(front: String): Boolean {
            val open = front.indexOf(CLOZE_OPEN)
            if (open < 0) return false
            val answerStart = open + CLOZE_OPEN.length
            val close = front.indexOf(CLOZE_CLOSE, answerStart)
            return close > answerStart && front.substring(answerStart, close).isNotBlank()
        }
    }
}

/**
 * Everything the approval queue renders (docs/android.md, "One UiState per
 * screen").
 *
 * [pendingCount] is the header's "N pending" — the whole queue, from the
 * collection-wide `counts` on `GET /cards/pending` (G1, issue #132), never
 * the loaded list's length. [bulkApprovableCount] backs the bulk action
 * (issue #168) and comes from the same `counts` for the same reason;
 * `needs_human` is excluded from it, and the server refuses those ids per
 * card besides (hard rule 1).
 *
 * [truncated] source highlights are a flag, never a repair (hard rule 7):
 * nothing here offers to reconstruct clipped text.
 */
data class ApproveUiState(
    val groups: List<ChapterGroup> = emptyList(),
    val pendingCount: Int? = null,
    val filter: QueueFilter = QueueFilter.ALL,
    val isLoading: Boolean = true,
    val isOffline: Boolean = false,
    val isUnauthorized: Boolean = false,
    val expandedHighlightCardIds: Set<Long> = emptySet(),
    val editingCardId: Long? = null,
    val busyCardId: Long? = null,
    /** Cards in the collection-wide `pending_review` bucket — the bulk action's N. */
    val bulkApprovableCount: Int? = null,
    val isBulkApproving: Boolean = false,
    /**
     * The bulk confirm dialog is open (issue #266): the pill tap arms it
     * instead of approving, because one stray tap approved 152 cards. Set by
     * the tap, cleared by confirm (which runs the bulk approve) or dismiss.
     */
    val showBulkApproveConfirm: Boolean = false,
    val errorMessage: String? = null,
    /**
     * Reject-reason drafts keyed by card id (issue #343): what the user typed
     * into the reject dialog, auto-saved per keystroke on the device and
     * seeded into the dialog when it reopens. Removed when the card is
     * approved or its reject succeeds; never sent anywhere — only the reject
     * confirm passes the text to the server.
     */
    val rejectReasonDrafts: Map<Long, String> = emptyMap(),
    /** The Curator's drops (issue #365), grouped by chapter in server order. */
    val droppedGroups: List<DroppedChapterGroup> = emptyList(),
    /** The header's "N dropped": `GET /units/dropped`'s collection-wide `count`, never the list length. */
    val droppedCount: Int? = null,
    /** The dropped unit with an action in flight; every Dropped action waits for it. */
    val busyDropUnitId: Long? = null,
    /** The dropped unit whose Generate call is running — it shows the progress state. */
    val generatingUnitId: Long? = null,
    /** A failed Dropped action's message, keyed by unit; the item stays listed. */
    val dropErrors: Map<Long, String> = emptyMap(),
    val manualCardDraft: ManualCardDraft? = null,
) {
    /**
     * The bulk bar shows only when there is clean work to do. It is hidden on
     * the NEEDS_YOU filter: every card there is `needs_human`, which the bulk
     * path excludes (hard rule 1), so the button would claim work it cannot do.
     * It is hidden on DROPPED too: it acts on cards, and none is listed there
     * (issue #365).
     */
    val showBulkApprove: Boolean
        get() =
            !isLoading &&
                filter == QueueFilter.ALL &&
                (bulkApprovableCount ?: 0) > 0

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
                // Dropped units are not cards; the Dropped list renders from droppedGroups.
                QueueFilter.DROPPED -> emptyList()
            }

    /** Source highlights are collapsed by default (design-system.md, "Source-highlight disclosure"). */
    fun isHighlightsExpanded(cardId: Long): Boolean = cardId in expandedHighlightCardIds

    /**
     * Queue-drained is its own state: centred success check, "Queue clear"
     * (design-system.md, "States"). It covers the card filters only — the
     * chip row stays above it, so drops are still reachable (issue #365).
     */
    val isQueueClear: Boolean
        get() = !isLoading && !isUnauthorized && filter != QueueFilter.DROPPED && groups.isEmpty()

    /** Dropped has its own empty state, "No dropped highlights" — never "Queue clear". */
    val isDroppedEmpty: Boolean
        get() = !isLoading && !isUnauthorized && filter == QueueFilter.DROPPED && droppedGroups.isEmpty()

    /** A Dropped action can start: online, and no other Dropped action in flight. */
    val dropActionsEnabled: Boolean
        get() = !isOffline && busyDropUnitId == null
}
