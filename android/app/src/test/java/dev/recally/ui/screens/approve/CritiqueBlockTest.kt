package dev.recally.ui.screens.approve

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Critique clamp (issue #150): critique length is unbounded by design (the
 * Writer ⇄ Critic loop runs up to 3 rounds, AGENTS.md hard rule 9), so a long
 * critique must collapse behind the same chevron disclosure idiom as the
 * source highlights — otherwise it buries the card's own action row. Runs on
 * the JVM under Robolectric; native graphics gives real text layout, which
 * the line-count assertions depend on. SDK is pinned to 34 like the other
 * Robolectric tests — 36 requires Java 21, the toolchain is 17.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CritiqueBlockTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** Eight paragraphs; wraps to well over the collapsed line count at 300dp. */
    private val longCritique =
        (1..8).joinToString("\n\n") { round ->
            "Round $round: the Writer kept the term ambiguous, and the Critic asked for a sharper referent each time."
        }

    @Test
    fun test_long_critique_is_clamped_by_default() {
        setCritiqueBlock(longCritique)

        composeTestRule.onNodeWithContentDescription("Expand critique").assertIsDisplayed()
        assertEquals(CRITIQUE_COLLAPSED_MAX_LINES, renderedLineCount(longCritique))
    }

    @Test
    fun test_expanding_reveals_the_full_critique() {
        setCritiqueBlock(longCritique)

        composeTestRule.onNodeWithContentDescription("Expand critique").performClick()

        composeTestRule.onNodeWithText(longCritique).assertIsDisplayed()
        val expandedLineCount = renderedLineCount(longCritique)
        assertTrue(
            "expanded critique must render beyond the clamp, rendered $expandedLineCount lines",
            expandedLineCount > CRITIQUE_COLLAPSED_MAX_LINES,
        )
        composeTestRule.onNodeWithContentDescription("Collapse critique").assertIsDisplayed()
    }

    @Test
    fun test_short_critique_needs_no_disclosure() {
        val shortCritique = "Potentially ambiguous term."
        setCritiqueBlock(shortCritique)

        composeTestRule.onNodeWithText(shortCritique).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand critique").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Collapse critique").assertDoesNotExist()
        assertEquals(1, renderedLineCount(shortCritique))
    }

    /** A fixed 300dp width keeps the wrapping deterministic across test devices. */
    private fun setCritiqueBlock(critique: String) {
        composeTestRule.setContent {
            RecallyTheme {
                Box(Modifier.width(300.dp)) {
                    CritiqueBlock(critique = critique)
                }
            }
        }
    }

    /** -1 when the block reports no line count, so a missing property fails the test. */
    private fun renderedLineCount(critique: String): Int =
        composeTestRule
            .onNodeWithText(critique)
            .fetchSemanticsNode()
            .config
            .getOrNull(CritiqueLineCountKey) ?: -1
}
