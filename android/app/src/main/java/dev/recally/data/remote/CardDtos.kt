package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /cards/pending` response (docs/api-spec.md, "Approval queue"). */
@Serializable
data class PendingCardsResponse(
    val cards: List<PendingCardDto>,
    val counts: PendingCountsDto,
)

/**
 * Collection-wide queue totals. They ignore the route's filters by design
 * (docs/api-spec.md) — Today's tiles read them rather than `cards.size`.
 */
@Serializable
data class PendingCountsDto(
    @SerialName("pending_review") val pendingReview: Int,
    @SerialName("needs_human") val needsHuman: Int,
)

/** One card in the approval queue. */
@Serializable
data class PendingCardDto(
    val id: Long,
    val status: String,
    val type: String,
    val front: String,
    val back: String,
    @SerialName("status_reason") val statusReason: String?,
    @SerialName("source_highlights") val sourceHighlights: List<String>,
    val truncated: Boolean,
    @SerialName("book_id") val bookId: Long,
    val book: String,
    val chapter: String?,
)

/**
 * `POST /cards/{id}/approve` request — optional edits; omitted (null) fields
 * keep the Writer's text because the serializer drops nulls.
 */
@Serializable
data class ApproveCardRequest(
    val front: String? = null,
    val back: String? = null,
)

/**
 * `POST /cards/{id}/reject` request — the reason is optional
 * (docs/android.md, "Screens → 3. Approval queue") and feeds the Learner when
 * given. Null is dropped from the body (`explicitNulls = false`).
 */
@Serializable
data class RejectCardRequest(
    val reason: String? = null,
)

/**
 * `PATCH /cards/{id}` request — any of front/back/tags; null fields are
 * omitted from the body so the server leaves them alone.
 */
@Serializable
data class EditCardRequest(
    val front: String? = null,
    val back: String? = null,
    val tags: List<String>? = null,
)

/**
 * The card returned by approve / reject / `PATCH /cards/{id}`
 * (docs/api-spec.md), so the client can update its cache.
 */
@Serializable
data class CardDto(
    val id: Long,
    val status: String,
    val type: String,
    val front: String,
    val back: String,
    @SerialName("original_front") val originalFront: String,
    @SerialName("original_back") val originalBack: String,
    @SerialName("status_reason") val statusReason: String?,
    @SerialName("approved_at") val approvedAt: String?,
)

/** Response of bury / suspend / unsuspend (null when the card is in rotation). */
@Serializable
data class SuspendedUntilResponse(
    @SerialName("suspended_until") val suspendedUntil: String?,
)

/** The bulk-approve body (issue #168, docs/api-spec.md, `POST /cards/approve-batch`). */
@Serializable
data class ApproveBatchRequest(
    @SerialName("card_ids") val cardIds: List<Long>,
)

/**
 * One card's outcome. `ok` false carries the status the single-card route
 * would have raised, so a `needs_human` refusal (409) is distinguishable from
 * an unknown id (404).
 */
@Serializable
data class ApproveBatchResultDto(
    @SerialName("card_id") val cardId: Long,
    @SerialName("ok") val ok: Boolean,
    @SerialName("status") val status: String? = null,
    @SerialName("error_status") val errorStatus: Int? = null,
    @SerialName("detail") val detail: String? = null,
)

/** Exactly one entry per request id, in request order (docs/api-spec.md). */
@Serializable
data class ApproveBatchResponseDto(
    @SerialName("results") val results: List<ApproveBatchResultDto>,
)
