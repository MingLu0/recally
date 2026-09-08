package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /reviews/due` (docs/api-spec.md, "Reviews"). */
@Serializable
data class DueCardsResponse(
    @SerialName("due_count") val dueCount: Int,
    @SerialName("new_count") val newCount: Int,
    @SerialName("learning_steps_minutes") val learningStepsMinutes: List<Int>,
    val cards: List<DueCardDto>,
)

/** One card in the due queue; `step` is null for a card in `review`. */
@Serializable
data class DueCardDto(
    val id: Long,
    @SerialName("unit_id") val unitId: Long,
    val type: String,
    val front: String,
    val back: String,
    @SerialName("book_id") val bookId: Long,
    val book: String,
    val chapter: String?,
    val tags: List<String>,
    val state: String,
    val step: Int?,
    val due: String,
)

/** `POST /reviews/{card_id}/rate` request. `device_id` is omitted when null. */
@Serializable
data class RateRequest(
    val rating: Int,
    @SerialName("response_ms") val responseMs: Long,
    @SerialName("rated_at") val ratedAt: String,
    @SerialName("device_id") val deviceId: Long? = null,
)

/** `POST /reviews/{card_id}/rate` response. */
@Serializable
data class RateResponse(
    @SerialName("card_id") val cardId: Long,
    @SerialName("rated_at") val ratedAt: String,
    @SerialName("next_due") val nextDue: String,
    val state: String,
    val step: Int?,
    val lapsed: Boolean,
    val duplicate: Boolean,
)

/** `POST /reviews/rate-batch` request wrapper. */
@Serializable
data class RateBatchRequest(
    val ratings: List<RateBatchItemRequest>,
)

/** One queued rating in a batch — the rate payload plus its `card_id`. */
@Serializable
data class RateBatchItemRequest(
    @SerialName("card_id") val cardId: Long,
    val rating: Int,
    @SerialName("response_ms") val responseMs: Long,
    @SerialName("rated_at") val ratedAt: String,
    @SerialName("device_id") val deviceId: Long? = null,
)

/** `POST /reviews/rate-batch` response. */
@Serializable
data class RateBatchResponse(
    val results: List<RateBatchResultDto>,
)

/**
 * One per-item verdict, in request order (docs/api-spec.md). Success fields
 * are null on a failed item; `status`/`detail` are null on a successful one.
 */
@Serializable
data class RateBatchResultDto(
    @SerialName("card_id") val cardId: Long,
    @SerialName("rated_at") val ratedAt: String,
    val ok: Boolean,
    @SerialName("next_due") val nextDue: String? = null,
    val state: String? = null,
    val step: Int? = null,
    val lapsed: Boolean? = null,
    val duplicate: Boolean? = null,
    val status: Int? = null,
    val detail: String? = null,
)
