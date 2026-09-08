package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /cards/pending` response (docs/api-spec.md, "Approval queue"). */
@Serializable
data class PendingCardsResponse(
    val cards: List<PendingCardDto>,
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
