package dev.recally.ui.screens.review

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The session progress bar's fill fraction, tested through the pure
 * [sessionProgressFillWeights] helper (docs/design/design-system.md,
 * "Session progress").
 */
class SessionProgressTest {
    @Test
    fun test_progress_denominator_counts_each_card_once() {
        // One card done, one repeat pending, two cards left — `cardsLeft`
        // already counts the repeat, so three cards exist in total and each
        // must enter the denominator exactly once: the fill is 2/3, the
        // empty track is the one card still on screen.
        val (doneWeight, repeatWeight) =
            sessionProgressFillWeights(doneCount = 1, toRepeatCount = 1, cardsLeft = 2)

        assertEquals(1.0 / 3.0, doneWeight.toDouble(), 1e-6)
        assertEquals(1.0 / 3.0, repeatWeight.toDouble(), 1e-6)
        assertEquals(
            "a repeat counted beside cardsLeft as well as inside it under-fills the bar (#148)",
            2.0 / 3.0,
            (doneWeight + repeatWeight).toDouble(),
            1e-6,
        )
    }
}
