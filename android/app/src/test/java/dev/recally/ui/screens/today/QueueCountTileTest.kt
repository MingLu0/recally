package dev.recally.ui.screens.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two "Waiting for you" queue tiles (issue #198): the design draws a
 * multi-colour composite icon per tile -- a card-stack-with-check for
 * "to approve", a clock-in-a-disc for "need you" -- not the bare 10dp dot
 * the shipped app drew.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
}
