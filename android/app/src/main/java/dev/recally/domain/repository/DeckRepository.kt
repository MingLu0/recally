package dev.recally.domain.repository

import dev.recally.domain.model.Deck
import dev.recally.domain.model.DeckCard

/**
 * Books with card counts (docs/api-spec.md, GET /decks). Remote-only for v1 —
 * only due cards are cached for offline use (docs/android.md,
 * "Offline-first sync").
 */
interface DeckRepository {
    suspend fun decks(): Result<List<Deck>>

    /**
     * The browse list for one book (docs/api-spec.md,
     * `GET /decks/{book_id}/cards`). Unpaginated in v1 by decision; passing
     * [chapter] asks the server to filter, so an expanded chapter does not
     * require holding the whole book in the UI. Suspended cards are included
     * — the browse view is the only way back from a suspend (ADR-008).
     */
    suspend fun deckCards(
        bookId: Long,
        chapter: String? = null,
    ): Result<List<DeckCard>>
}
