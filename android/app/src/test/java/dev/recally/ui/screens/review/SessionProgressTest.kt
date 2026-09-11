package dev.recally.ui.screens.review

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.foundation.layout.width as widthModifier

/**
 * The session progress bar's fill fraction, tested both through the pure
 * [sessionProgressFillWeights] helper and — the assertion that actually
 * catches issue #200 — through the *rendered* segment widths
 * (docs/design/design-system.md, "Session progress").
 *
 * The helper alone cannot fail on #200: it already returns the right
 * fractions, and the bug is the composable discarding them (no remainder
 * child in the inner `Row`, so `weight` normalises across only the segments
 * present and always fills the track). The root here is fixed at
 * [TRACK_WIDTH]; every assertion measures the segment *child* node — a
 * width-constrained root can never fail (see ClozeTextTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionProgressTest {
    @get:Rule
    val composeTestRule = createComposeRule()

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

    @Test
    fun test_bar_is_not_full_when_half_the_session_remains() {
        // done=5, repeat=0, left=5: intended fill is 50%. Today the two fill
        // segments are the Row's only children, so `weight` normalises across
        // just them and the single `success` segment renders at 100% of the
        // track — this must be seen red before the fix exists.
        setProgress(doneCount = 5, toRepeatCount = 0, cardsLeft = 5)

        val trackWidth = trackBounds()
        val doneWidth = segmentBounds(DONE_TAG)

        assertTrue(
            "the done segment must be strictly narrower than the track when half the session remains: " +
                "done=${doneWidth}px, track=${trackWidth}px",
            doneWidth < trackWidth - TOLERANCE_PX,
        )
        assertApproxFraction(doneWidth, trackWidth, 0.5)
    }

    @Test
    fun test_bar_is_not_full_after_a_single_rating() {
        // done=1, repeat=0, left=9: intended fill is 10%, not full.
        setProgress(doneCount = 1, toRepeatCount = 0, cardsLeft = 9)

        val trackWidth = trackBounds()
        val doneWidth = segmentBounds(DONE_TAG)

        assertTrue(
            "a single rating must not fill the bar: done=${doneWidth}px, track=${trackWidth}px",
            doneWidth < trackWidth - TOLERANCE_PX,
        )
        assertApproxFraction(doneWidth, trackWidth, 0.1)
    }

    @Test
    fun test_bar_is_empty_before_the_first_rating() {
        // done=0, repeat=0, left=10: no segment is drawn at all.
        setProgress(doneCount = 0, toRepeatCount = 0, cardsLeft = 10)

        composeTestRule.onNodeWithTag(DONE_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(REPEAT_TAG).assertDoesNotExist()
    }

    @Test
    fun test_bar_fills_completely_when_nothing_is_left() {
        // done=10, repeat=0, left=0: the done segment spans the whole track,
        // and the remainder spacer (weight 0f) must not crash Compose, which
        // rejects an exactly-zero weight.
        setProgress(doneCount = 10, toRepeatCount = 0, cardsLeft = 0)

        val trackWidth = trackBounds()
        val doneWidth = segmentBounds(DONE_TAG)

        assertApproxFraction(doneWidth, trackWidth, 1.0)
    }

    @Test
    fun test_done_and_repeat_segments_are_proportional_to_each_other() {
        // done=6, repeat=1, left=5: eleven cards total, green ~6/11, amber
        // ~1/11, and the two together must leave a visible remainder.
        setProgress(doneCount = 6, toRepeatCount = 1, cardsLeft = 5)

        val trackWidth = trackBounds()
        val doneWidth = segmentBounds(DONE_TAG)
        val repeatWidth = segmentBounds(REPEAT_TAG)

        assertApproxFraction(doneWidth, trackWidth, 6.0 / 11.0)
        assertApproxFraction(repeatWidth, trackWidth, 1.0 / 11.0)
        assertTrue(
            "done and repeat together must leave a visible remainder: " +
                "done=${doneWidth}px, repeat=${repeatWidth}px, track=${trackWidth}px",
            doneWidth + repeatWidth < trackWidth - TOLERANCE_PX,
        )
    }

    private fun setProgress(
        doneCount: Int,
        toRepeatCount: Int,
        cardsLeft: Int,
    ) {
        composeTestRule.setContent {
            RecallyTheme {
                SessionProgress(
                    doneCount = doneCount,
                    toRepeatCount = toRepeatCount,
                    cardsLeft = cardsLeft,
                    modifier = Modifier.widthModifier(TRACK_WIDTH),
                )
            }
        }
    }

    private fun trackBounds(): Float =
        composeTestRule
            .onNodeWithTag(TRACK_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.width

    private fun segmentBounds(tag: String): Float =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .boundsInRoot.width

    private fun assertApproxFraction(
        segmentWidth: Float,
        trackWidth: Float,
        expectedFraction: Double,
    ) {
        val actual = segmentWidth / trackWidth
        assertEquals(expectedFraction, actual.toDouble(), FRACTION_TOLERANCE)
    }

    private companion object {
        val TRACK_WIDTH = 300.dp
        const val TOLERANCE_PX = 1f
        const val FRACTION_TOLERANCE = 0.05
        const val TRACK_TAG = "session_progress_track"
        const val DONE_TAG = "session_progress_done"
        const val REPEAT_TAG = "session_progress_repeat"
    }
}
