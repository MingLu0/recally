package dev.recally.domain.model

import java.time.Instant

/**
 * The server's answer to one rating (docs/api-spec.md,
 * `POST /reviews/{card_id}/rate`). The client never computes scheduling
 * state itself; [step] is the card's new learning step as the server scored
 * it and **overwrites** the session's local re-queue counter
 * (docs/android.md, *Screens → 2. Review session*). [lapsed] is only
 * meaningful when [duplicate] is false — a replayed rating reports
 * `lapsed: false`, so the session summary counts lapses from non-duplicate
 * responses only.
 */
data class RateOutcome(
    val cardId: Long,
    val nextDue: Instant?,
    val state: String,
    val step: Int?,
    val lapsed: Boolean,
    val duplicate: Boolean,
)
