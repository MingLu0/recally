package dev.recally.ui.screens.decks

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "9 Sep" — the artboard's date form for a due more than a day out. */
private val DUE_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/**
 * The browse row's due label (docs/design/RcBook.dc.html: "Due in 4h", "9 Sep").
 *
 * This is **display formatting of the server's `card_state.due`** and nothing
 * more — the value arrives on `GET /decks/{book_id}/cards` and the client never
 * derives a schedule of its own (hard rule 5, ADR-005). A card the server sends
 * without a [due] gets no label at all rather than an invented date: a fresh
 * card holds an approval-time marker the server deliberately withholds, and a
 * date on that row would read as an interval no review produced.
 *
 * Within the next day the label counts down ("Due in 4h", "Due now" once the
 * card is already available); beyond that it names the date, as the artboard
 * does. Both are the same instant, rendered.
 */
fun deckCardDueLabel(
    due: Instant?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (due == null) return null
    val until = Duration.between(now, due)
    return when {
        until.isNegative || until.isZero -> "Due now"
        until.toHours() < 24 ->
            if (until.toHours() >= 1) {
                "Due in ${until.toHours()}h"
            } else {
                "Due in ${until.toMinutes().coerceAtLeast(1)}m"
            }
        else -> DUE_DATE_FORMAT.format(due.atZone(zone))
    }
}
