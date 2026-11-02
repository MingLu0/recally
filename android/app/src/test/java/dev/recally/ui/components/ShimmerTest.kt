package dev.recally.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The shared loading shimmer (issue #344, design-system.md "States →
 * Loading"): a `line-soft` placeholder swept by a diagonal highlight band on
 * an infinite linear loop. These tests pin the two things every screen-level
 * test relies on — the highlight actually moves (a static fill would leave
 * pixels identical between frames) and the modifier never changes the
 * placeholder's layout bounds, in either theme.
 *
 * Pixels are captured the way [dev.recally.ui.screens.approve.ApproveScreenTest]
 * does it — the compose root view drawn into a software bitmap, because
 * `captureToImage()` times out under Robolectric (see
 * [dev.recally.ui.screens.today.QueueCountTileTest]). The test clock is driven
 * manually (`autoAdvance = false`) so the two frames are exactly half a
 * shimmer cycle apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShimmerTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun test_shimmer_highlight_moves_when_the_test_clock_advances() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            RecallyTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.recallyColors.ground)) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(SHIMMER_PLACEHOLDER_WIDTH, SHIMMER_PLACEHOLDER_HEIGHT)
                            .shimmer(),
                    )
                }
            }
        }
        // Pump a frame at t=0 so the first capture reflects the composed
        // state, not an empty window.
        composeTestRule.mainClock.advanceTimeBy(0)
        composeTestRule.waitForIdle()

        val bounds = composeTestRule.onNodeWithTag(SHIMMER_TAG).getBoundsInRoot()
        val before = captureComposeRoot()

        composeTestRule.mainClock.advanceTimeBy(SHIMMER_DURATION_MS / 2L)
        composeTestRule.waitForIdle()
        val after = captureComposeRoot()

        // The sweep must not disturb the placeholder's geometry: same node,
        // same bounds, half a cycle later.
        val boundsAfter = composeTestRule.onNodeWithTag(SHIMMER_TAG).getBoundsInRoot()
        assertEquals("shimmer left the placeholder's bounds alone", bounds.left, boundsAfter.left)
        assertEquals("shimmer left the placeholder's bounds alone", bounds.top, boundsAfter.top)
        assertEquals("shimmer left the placeholder's bounds alone", bounds.right, boundsAfter.right)
        assertEquals("shimmer left the placeholder's bounds alone", bounds.bottom, boundsAfter.bottom)

        // A strip of samples through the placeholder's vertical middle. Half
        // a cycle moves the band to the opposite side, so at least one sample
        // must have changed colour; a static `line-soft` fill changes none.
        val sampleY = bounds.centreYPx()
        val changed =
            SAMPLE_X_FRACTIONS.count { fraction ->
                val sampleX = bounds.xAtFraction(fraction)
                before.getPixel(sampleX, sampleY) != after.getPixel(sampleX, sampleY)
            }
        assertTrue(
            "expected the highlight band to move across the placeholder " +
                "($changed of ${SAMPLE_X_FRACTIONS.size} samples changed)",
            changed > 0,
        )
    }

    @Test
    fun test_shimmer_preserves_skeleton_bounds_in_light_and_dark_themes() {
        var darkTheme by mutableStateOf(false)
        composeTestRule.setContent {
            RecallyTheme(darkTheme = darkTheme) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(SHIMMER_PLACEHOLDER_WIDTH, SHIMMER_PLACEHOLDER_HEIGHT).shimmer())
                }
            }
        }
        composeTestRule.waitForIdle()
        val lightBounds = composeTestRule.onNodeWithTag(SHIMMER_TAG).getBoundsInRoot()

        darkTheme = true
        composeTestRule.waitForIdle()
        val darkBounds = composeTestRule.onNodeWithTag(SHIMMER_TAG).getBoundsInRoot()

        // The shimmer replaces only the fill treatment: the placeholder keeps
        // its measured size under both themes, so existing layouts do not move.
        assertEquals("same width in both themes", lightBounds.width, darkBounds.width)
        assertEquals("same height in both themes", lightBounds.height, darkBounds.height)
        assertEquals(lightBounds.left, darkBounds.left)
        assertEquals(lightBounds.top, darkBounds.top)
    }

    /**
     * Draws the compose root view into a software bitmap — the capture path
     * that works under Robolectric (see class doc). Coordinates match
     * `getBoundsInRoot()` once scaled by density.
     */
    private fun captureComposeRoot(): Bitmap {
        val view = composeTestRule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun DpRect.centreYPx(): Int = ((top.value + bottom.value) / 2f).toPx()

    private fun DpRect.xAtFraction(fraction: Float): Int = (left.value + width.value * fraction).toPx()

    private fun Float.toPx(): Int = (this * composeTestRule.density.density).roundToInt()

    private companion object {
        val SHIMMER_PLACEHOLDER_WIDTH = 200.dp
        val SHIMMER_PLACEHOLDER_HEIGHT = 48.dp

        /** Where along the placeholder's width the pixel strip is sampled. */
        val SAMPLE_X_FRACTIONS = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)
    }
}
