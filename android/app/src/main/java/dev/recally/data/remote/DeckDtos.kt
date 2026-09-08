package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for GET /decks, matching docs/api-spec.md field for field.
 */
@Serializable
data class DeckDto(
    @SerialName("book_id") val bookId: Long,
    val title: String,
    val total: Int,
    val due: Int,
)

@Serializable
data class DecksDto(
    val decks: List<DeckDto>,
)
