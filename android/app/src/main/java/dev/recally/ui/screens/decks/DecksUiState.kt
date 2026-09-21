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
    /**
     * The import tile's upload state machine (issue #234). Idle until an
     * export is picked; [ImportState.Uploading] for the whole server-side
     * wait — the pipeline reports no progress, so the tile shows an
     * indeterminate state rather than a lying percentage; then one result.
     * Never touches [decks]: a failed import must leave the list intact.
     */
    val importState: ImportState = ImportState.Idle,
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

/**
 * The import tile's upload lifecycle (issue #234). Each failure is its own
 * case — unreachable, 401, "not a valid export" — so the tile can name the
 * fix ("check Settings", "the backend is off") instead of reporting a generic
 * failure, mirroring the connection-test convention (docs/android.md,
 * "Connecting to the backend").
 */
sealed interface ImportState {
    /** Nothing picked yet; the tile offers the picker. */
    data object Idle : ImportState

    /** Bytes are up; the server runs ingest and the pipeline. No progress
     * exists to show — the pipeline reports none — so this is deliberately
     * indeterminate. */
    data object Uploading : ImportState

    /** The run row came back; the tile reports what the export did. */
    data class Success(
        val rowsNew: Int,
        val rowsUpdated: Int,
        val rowsRemoved: Int,
    ) : ImportState

    /** The backend could not be reached at all. */
    data object Unreachable : ImportState

    /** 401 — the API key is wrong; the fix lives in Settings. */
    data object Unauthorized : ImportState

    /** 422 — the picked file is not an export the adapter can parse. */
    data class InvalidExport(
        val detail: String?,
    ) : ImportState

    /** A failure that is neither of the above: an unexpected server fault. */
    data class Unexpected(
        val detail: String?,
    ) : ImportState
}
