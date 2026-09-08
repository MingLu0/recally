package dev.recally.domain.model

/**
 * The due queue plus the FSRS learning steps in effect (docs/api-spec.md,
 * GET /reviews/due). [learningStepsMinutes] is what the review session uses
 * to re-queue Again/Hard cards inside the session.
 */
data class DueSummary(
    val dueCount: Int,
    val newCount: Int,
    val learningStepsMinutes: List<Int>,
    val cards: List<Card>,
)
