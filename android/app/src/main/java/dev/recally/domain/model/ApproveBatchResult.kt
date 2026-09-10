package dev.recally.domain.model

/**
 * One card's outcome from a bulk approve (issue #168, docs/api-spec.md,
 * `POST /cards/approve-batch`).
 *
 * A failed entry is not an error for the whole action: an unknown id, an
 * already-decided card and a `needs_human` card each fail only themselves, so
 * the queue-clearing action survives one stale id. The server owns the
 * `needs_human` refusal (hard rule 1) — the client does not decide it.
 */
data class ApproveBatchResult(
    val cardId: Long,
    val ok: Boolean,
    val detail: String? = null,
)
