package dev.recally.ui

import java.time.Duration
import java.time.Instant

/**
 * The hours-away figure behind "next card in 4 hours" (design-system.md,
 * "States"; issue #134). This is display formatting of the server's
 * `next_due_at` — never a client-computed schedule (hard rule 5).
 *
 * A whole hour or more reads "in N hours"; under an hour it reads "in N min"
 * so a near-term due never rounds down to the forbidden "in 0 hours".
 */
fun formatNextDueIn(
    now: Instant,
    nextDueAt: Instant,
): String {
    val until = Duration.between(now, nextDueAt)
    val hours = until.toHours()
    return if (hours >= 1) {
        "in $hours ${if (hours == 1L) "hour" else "hours"}"
    } else {
        "in ${until.toMinutes().coerceAtLeast(1)} min"
    }
}
