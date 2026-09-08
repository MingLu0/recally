package dev.recally.domain.repository

import dev.recally.domain.model.Deck

/**
 * Books with card counts (docs/api-spec.md, GET /decks). Remote-only for v1 —
 * only due cards are cached for offline use (docs/android.md,
 * "Offline-first sync").
 */
interface DeckRepository {
    suspend fun decks(): Result<List<Deck>>
}
