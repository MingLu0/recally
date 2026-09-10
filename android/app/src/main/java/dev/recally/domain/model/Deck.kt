package dev.recally.domain.model

/**
 * A book with its card counts (docs/api-spec.md, GET /decks). [progress] is
 * the share of the book's approved cards in FSRS `review` state
 * (docs/data-model.md, `card_state`); 0 for a book with no approved cards.
 *
 * [chapters] is the distinct-chapter count behind the row's
 * "48 cards · 9 chapters" (issue #172). It is a server field because the Decks
 * screen never fetches a book's cards to count them itself.
 *
 * [truncated] is the count of the book's clipped source highlights behind the
 * row's "2 TRUNCATED" badge (G6, issue #173). Unlike the counts above it spans
 * every card status — truncation is a property of the O'Reilly export, not of
 * the approved population. It is a flag only: nothing here or on any screen
 * offers to reconstruct the lost text (AGENTS.md hard rule 7).
 */
data class Deck(
    val bookId: Long,
    val title: String,
    val total: Int,
    val due: Int,
    val progress: Float,
    val chapters: Int,
    val truncated: Int,
)
