package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /units/dropped` response (docs/api-spec.md, "Dropped units"; issue #365). */
@Serializable
data class DroppedUnitsResponse(
    val count: Int,
    val units: List<DroppedUnitDto>,
)

/**
 * One Curator drop. `created_at`, `drop_resolution` and `drop_resolved_at`
 * are in the payload but unused by the app, so they are not declared here
 * (`ignoreUnknownKeys`).
 */
@Serializable
data class DroppedUnitDto(
    val id: Long,
    val reason: String? = null,
    @SerialName("curated_text") val curatedText: String,
    @SerialName("source_highlights") val sourceHighlights: List<String>,
    val truncated: Boolean,
    @SerialName("book_id") val bookId: Long,
    val book: String,
    val chapter: String? = null,
)

/** `POST /units/{id}/generate` response: the cards written, in the queue's card shape. */
@Serializable
data class GeneratedCardsResponse(
    val cards: List<PendingCardDto>,
)

/** `POST /units/{id}/cards` request — a card the human typed. A cloze's `back` may be empty. */
@Serializable
data class HandWrittenCardRequest(
    val type: String,
    val front: String,
    val back: String,
)
