package dev.recally.ui.screens.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The two "Waiting for you" queue tiles (issue #198): the design draws a
 * multi-colour composite icon per tile -- a card-stack-with-check for
 * "to approve", a clock-in-a-disc for "need you" -- not the bare 10dp dot
 * the shipped app drew.
 *
 * Also covers issue #199: `QueueCountTile`'s `.border()` declares a rounded
 * shape but `Modifier.clip()` is what actually clips the press ripple to it
 * -- without a preceding `.clip()`, the indication paints the tile's square
 * layout bounds instead. `captureToImage()` cannot prove this under
 * Robolectric: its window-capture path (`WindowCapture_androidKt.forceRedraw`)
 * times out here even with `@GraphicsMode(NATIVE)` and a manually-stepped
 * `mainClock` -- Robolectric's simulated window never satisfies the redraw
 * condition captureToImage waits on. Tried and confirmed failing with
 * `ComposeTimeoutException` at the `captureToImage()` call itself, both with
 * and without a manual clock; not a flake, so [test_queue_tile_is_clipped_to_its_corner_radius]
 * asserts the same thing [dev.recally.ui.ClickableClipTest] does, scoped to
 * this one site: the tile renders (proving [QUEUE_COUNT_TILE_TAG] resolves to
 * the real node) and its modifier chain in source clips before it clicks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueueCountTileTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun test_to_approve_tile_renders_its_icon() {
        composeTestRule.setContent {
            RecallyTheme {
                QueueCountTile(
                    count = 8,
                    label = "to approve",
                    icon = QueueTileIcon.TO_APPROVE,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("to approve icon").assertIsDisplayed()
    }

    @Test
    fun test_need_you_tile_renders_its_icon() {
        composeTestRule.setContent {
            RecallyTheme {
                QueueCountTile(
                    count = 3,
                    label = "need you",
                    icon = QueueTileIcon.NEEDS_HUMAN,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("need you icon").assertIsDisplayed()
    }

    @Test
    fun test_queue_tile_no_longer_renders_the_bare_dot() {
        // Negative: the shipped tile drew a plain 10dp coloured dot in place
        // of a mark. The design's composite icons are 26dp -- the mark node
        // must not still be the bare dot's 10dp square.
        composeTestRule.setContent {
            RecallyTheme {
                QueueCountTile(
                    count = 8,
                    label = "to approve",
                    icon = QueueTileIcon.TO_APPROVE,
                    onClick = {},
                )
            }
        }

        val markBounds =
            composeTestRule.onNodeWithContentDescription("to approve icon").getBoundsInRoot()
        assertNotEquals("mark is no longer the bare 10dp dot", 10.dp, markBounds.width)
        assertNotEquals("mark is no longer the bare 10dp dot", 10.dp, markBounds.height)
    }

    @Test
    fun test_the_two_tile_icons_are_distinct() {
        assertEquals(2, QueueTileIcon.entries.toSet().size)
    }

    @Test
    fun test_queue_tile_is_clipped_to_its_corner_radius() {
        composeTestRule.setContent {
            RecallyTheme {
                QueueCountTile(
                    count = 8,
                    label = "to approve",
                    icon = QueueTileIcon.TO_APPROVE,
                    onClick = {},
                )
            }
        }

        // Proves QUEUE_COUNT_TILE_TAG resolves to the rendered tile node —
        // a missing/renamed tag would make the source check below vacuous.
        composeTestRule.onNodeWithTag(QUEUE_COUNT_TILE_TAG).assertExists()

        val source = File("src/main/java/dev/recally/ui/screens/today/TodayScreen.kt").readText()
        val tileChain = source.substringAfter("fun QueueCountTile(").substringBefore("\n}\n")

        val borderCall = Regex("""\.border\((?:[^()]|\([^()]*\))*RoundedCornerShape(?:[^()]|\([^()]*\))*\)""").find(tileChain)
        val clickableCall = Regex("""\.clickable\(""").find(tileChain)
        val clipCall = Regex("""\.clip\(""").find(tileChain)

        assertTrue("expected QueueCountTile to declare a rounded .border(", borderCall != null)
        assertTrue("expected QueueCountTile to declare .clickable(", clickableCall != null)
        assertTrue(
            "expected QueueCountTile's modifier chain to clip before its rounded border/clickable, " +
                "otherwise the press ripple fills the square layout bounds instead of the rounded corner",
            clipCall != null && clipCall.range.first < clickableCall!!.range.first,
        )
    }
}
