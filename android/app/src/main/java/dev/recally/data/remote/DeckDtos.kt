package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /decks` response (docs/api-spec.md, "Decks & browsing"). */
@Serializable
data class DeckListResponse(
    val decks: List<DeckDto>,
)

/** One book with its card counts and progress. */
@Serializable
data class DeckDto(
    @SerialName("book_id") val bookId: Long,
    val title: String,
    val total: Int,
    val due: Int,
    val progress: Float,
)

/** `GET /decks/{book_id}/cards` response. */
@Serializable
data class DeckCardsResponse(
    val cards: List<DeckCardDto>,
)

/** One card in the per-book browse list; `suspendedUntil` is null in rotation. */
@Serializable
data class DeckCardDto(
    val id: Long,
    val type: String,
    val front: String,
    val back: String,
    val chapter: String?,
    val tags: List<String>,
    @SerialName("suspended_until") val suspendedUntil: String?,
)
