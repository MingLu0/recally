package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for GET /reviews/due, matching docs/api-spec.md field for field
 * — no invented fields. Unknown fields are ignored by the configured Json so
 * a server-side addition cannot break the client.
 */
@Serializable
data class DueCardDto(
    val id: Long,
    @SerialName("unit_id") val unitId: Long,
    val type: String,
    val front: String,
    val back: String,
    @SerialName("book_id") val bookId: Long,
    val book: String,
    val chapter: String,
    val tags: List<String> = emptyList(),
    val state: String,
    val step: Int? = null,
    val due: String? = null,
)

@Serializable
data class DueSummaryDto(
    @SerialName("due_count") val dueCount: Int,
    @SerialName("new_count") val newCount: Int,
    @SerialName("learning_steps_minutes") val learningStepsMinutes: List<Int>,
    val cards: List<DueCardDto>,
)
