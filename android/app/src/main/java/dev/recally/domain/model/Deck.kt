package dev.recally.domain.model

/**
 * A book with its card counts (docs/api-spec.md, GET /decks). [progress] is
 * the share of the book's approved cards in FSRS `review` state
 * (docs/data-model.md, `card_state`); 0 for a book with no approved cards.
 *
 * [chapters] is the distinct-chapter count behind the row's
 * "48 cards · 9 chapters" (issue #172). It is a server field because the Decks
 * screen never fetches a book's cards to count them itself.
 */
data class Deck(
    val bookId: Long,
    val title: String,
    val total: Int,
    val due: Int,
    val progress: Float,
    val chapters: Int,
)
