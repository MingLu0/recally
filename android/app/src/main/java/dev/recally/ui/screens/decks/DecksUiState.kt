package dev.recally.ui.screens.decks

import dev.recally.domain.model.Deck
import dev.recally.domain.model.DeckCard

/**
 * One immutable UiState for both deck screens (docs/android.md, "One UiState
 * per screen"). [isBookDetail] selects which of them renders it: the plain
 * `decks` route fills [decks]; the parameterised `decks/{bookId}` route fills
 * [bookTitle], [chapters] and [expandedCards].
 *
 * Deliberately absent: per-book progress (G2), per-card FSRS state/due and
 * chapter counts from the server (G5), truncated counts (G6) — the endpoints
 * document none of them and the ticket scopes them out.
 */
data class DecksUiState(
    val isBookDetail: Boolean = false,
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val isUnauthorized: Boolean = false,
    val errorMessage: String? = null,
    // Deck list mode
    val decks: List<Deck> = emptyList(),
    // Book detail mode
    val bookId: Long? = null,
    val bookTitle: String? = null,
    val chapters: List<ChapterSummary> = emptyList(),
    val expandedChapter: String? = null,
    val expandedCards: List<DeckCard> = emptyList(),
    val isChapterLoading: Boolean = false,
    val editingCard: DeckCard? = null,
) {
    /**
     * Edit, suspend and unsuspend all require connectivity — they are
     * disabled offline rather than queued (docs/android.md,
     * "Offline-first sync"; ratings are the only queued write).
     */
    val cardControlsEnabled: Boolean
        get() = !isOffline

    val totalCards: Int
        get() = decks.sumOf { it.total }
}

/**
 * One chapter in the book-detail list: name plus the count derived from the
 * unfiltered fetch (the endpoint carries `chapter` per card, so grouping is
 * client-side arithmetic over documented fields, not an invented field).
 * Cards without a chapter group under [UNGROUPED_CHAPTER].
 */
data class ChapterSummary(
    val name: String,
    val cardCount: Int,
)

const val UNGROUPED_CHAPTER = "Ungrouped"
