package dev.recally.domain.model

import java.time.Instant

/**
 * A flashcard as the client knows it (docs/api-spec.md, GET /reviews/due).
 *
 * Plain Kotlin — no Room or Retrofit annotations; the data layer maps to and
 * from its own entity/DTO types. [step] is the card's server-side FSRS
 * learning step at fetch time (null in `review`); the review session needs it
 * to re-queue Again/Hard cards at the right interval. [due] lets a cached
 * queue be re-sorted offline without a refetch.
 */
data class Card(
    val id: Long,
    val unitId: Long,
    val type: String,
    val front: String,
    val back: String,
    val bookId: Long,
    val book: String,
    val chapter: String,
    val tags: List<String>,
    val state: String,
    val step: Int?,
    val due: Instant?,
)
