package dev.recally.domain.model

/**
 * A book with its card counts (docs/api-spec.md, GET /decks). [progress] is
 * the share of the book's approved cards in FSRS `review` state
 * (docs/data-model.md, `card_state`); 0 for a book with no approved cards.
 */
data class Deck(
    val bookId: Long,
    val title: String,
    val total: Int,
    val due: Int,
    val progress: Float,
)
