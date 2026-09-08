package dev.recally.domain.model

/**
 * A book with its card counts (docs/api-spec.md, GET /decks).
 */
data class Deck(
    val bookId: Long,
    val title: String,
    val total: Int,
    val due: Int,
)
