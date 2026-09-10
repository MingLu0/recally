package dev.recally.ui.screens.decks

import dev.recally.domain.model.Deck
import dev.recally.domain.model.DeckCard

/**
 * One immutable UiState for both deck screens (docs/android.md, "One UiState
 * per screen"). [isBookDetail] selects which of them renders it: the plain
 * `decks` route fills [decks]; the parameterised `decks/{bookId}` route fills
 * [bookTitle], [chapters] and [expandedCards].
 *
 * Deliberately absent: truncated counts (G6) — the endpoints document none
 * and that ticket scopes them out. Per-book progress landed on `Deck`, and so
 * did the server's `chapters` count; per-card FSRS `state`/`due` landed on
 * `DeckCard` (issue #172), both rendered from the response, never computed.
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
    /**
     * The header's due count and progress (issue #152): the cards endpoint
     * documents neither, so they are resolved from `GET /decks` alongside the
     * title. Null until that list resolves — the header renders without them.
     */
    val bookDue: Int? = null,
    val bookProgress: Float? = null,
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
