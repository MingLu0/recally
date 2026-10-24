package dev.recally.ui.screens.approve

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Numbered expanded sources (issue #268): a grouped card can carry several
 * source highlights, and the expanded list labels each one "Source N" from 1
 * so a long group has a stable ordering cue and a particular source can be
 * discussed by number. Numbering restarts per component instance — the
 * disclosure renders what it is given, so the per-card restart is guarded at
 * screen level in [ApproveScreenTest]. Runs on the JVM under Robolectric; SDK
 * is pinned to 34 like the other Robolectric tests — 36 requires Java 21, the
 * toolchain is 17.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class HighlightDisclosureTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val highlights = listOf("First highlight.", "Second highlight.", "Third highlight.")

    @Test
    fun test_expanded_sources_are_numbered_from_one() {
        setHighlightDisclosure(expanded = true)

        composeTestRule.onNodeWithText("Source 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Source 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Source 3").assertIsDisplayed()

        // The repository supplies input order (server export position), so
        // the labels must appear top-to-bottom in that order.
        val topOf1 = composeTestRule.onNodeWithText("Source 1").getBoundsInRoot().top
        val topOf2 = composeTestRule.onNodeWithText("Source 2").getBoundsInRoot().top
        val topOf3 = composeTestRule.onNodeWithText("Source 3").getBoundsInRoot().top
        assertTrue("labels must follow input order", topOf1 < topOf2 && topOf2 < topOf3)
    }

    @Test
    fun test_collapsed_sources_do_not_show_numbered_labels() {
        var expanded by mutableStateOf(false)
        composeTestRule.setContent {
            RecallyTheme {
                Box(Modifier.width(300.dp)) {
                    HighlightDisclosure(
                        highlights = highlights,
                        truncated = false,
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                    )
                }
            }
        }

        // Collapsed: the count disclosure stays, the numbered labels are absent.
        composeTestRule.onNodeWithText("3 source highlights").assertIsDisplayed()
        composeTestRule.onNodeWithText("Source 1").assertDoesNotExist()
        composeTestRule.onNodeWithText("Source 2").assertDoesNotExist()
        composeTestRule.onNodeWithText("Source 3").assertDoesNotExist()

        // Control: expanding reveals the labels. Without this half the
        // negative above passes vacuously against a build that never renders
        // labels — a negative that has never failed is not evidence
        // (docs/workflow.md), so this test must fail red pre-implementation.
        composeTestRule.onNodeWithContentDescription("Expand source highlights").performClick()
        composeTestRule.onNodeWithText("Source 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Source 3").assertIsDisplayed()
    }

    /** A fixed 300dp width keeps the layout deterministic across test devices. */
    private fun setHighlightDisclosure(expanded: Boolean) {
        composeTestRule.setContent {
            RecallyTheme {
                Box(Modifier.width(300.dp)) {
                    HighlightDisclosure(
                        highlights = highlights,
                        truncated = false,
                        expanded = expanded,
                        onToggle = {},
                    )
                }
            }
        }
    }
}
