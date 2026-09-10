package dev.recally.ui.screens.approve

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
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
 * Chapter group header width distribution (issue #146): a long book title
 * must not squeeze the chapter into a narrow wrapped column — the header is
 * one line per docs/design/design-system.md, "Chapter group header", and
 * overflow degrades by ellipsis with the secondary chapter yielding first.
 *
 * Robolectric's legacy shadow text layout uses near-constant glyph metrics
 * (~1dp per char regardless of font size), so nothing overflows the 320dp
 * display; @GraphicsMode(NATIVE) is required for real text measurement. Even
 * then, real-world-length strings at 14sp do not reach 320dp, so the
 * overflow cases scale `labelLarge` up to [GIANT_SP] — the strings stay the
 * real ones from the issue; only the scale forces them past the row width,
 * which is exactly the device condition that triggered the bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChapterHeaderTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `chapter stays on one line when book title and chapter both overflow`() {
        composeTestRule.setContent {
            GiantTypeTheme {
                ChapterHeader(bookId = 1, book = LONG_TITLE, chapter = LONG_CHAPTER)
            }
        }

        val chapterNode = composeTestRule.onNodeWithText("· $LONG_CHAPTER")
        chapterNode.assertIsDisplayed()

        val titleBounds = composeTestRule.onNodeWithText(LONG_TITLE).getBoundsInRoot()
        val chapterBounds = chapterNode.getBoundsInRoot()
        assertEquals(
            "title and chapter share the single header line",
            titleBounds.height,
            chapterBounds.height,
        )
        assertEquals("same line, same top edge", titleBounds.top, chapterBounds.top)
    }

    @Test
    fun `long book title is ellipsised rather than pushing the chapter out`() {
        composeTestRule.setContent {
            GiantTypeTheme {
                ChapterHeader(bookId = 1, book = LONG_TITLE, chapter = SHORT_CHAPTER)
            }
        }

        // The title alone is wider than the row; the chapter must still be
        // present and visible with a non-zero width.
        val chapterNode = composeTestRule.onNodeWithText("· $SHORT_CHAPTER")
        chapterNode.assertIsDisplayed()

        val titleBounds = composeTestRule.onNodeWithText(LONG_TITLE).getBoundsInRoot()
        val chapterBounds = chapterNode.getBoundsInRoot()
        assertTrue("chapter keeps visible width", chapterBounds.width > Dp.Hairline)
        assertTrue(
            "title yields space to the chapter instead of consuming the row",
            titleBounds.right <= chapterBounds.left,
        )
    }

    @Test
    fun `short title and chapter are unchanged`() {
        composeTestRule.setContent {
            RecallyTheme {
                ChapterHeader(bookId = 1, book = SHORT_TITLE, chapter = SHORT_CHAPTER)
            }
        }

        val titleNode = composeTestRule.onNodeWithText(SHORT_TITLE)
        val chapterNode = composeTestRule.onNodeWithText("· $SHORT_CHAPTER")
        titleNode.assertIsDisplayed()
        chapterNode.assertIsDisplayed()

        val titleBounds = titleNode.getBoundsInRoot()
        val chapterBounds = chapterNode.getBoundsInRoot()
        assertEquals("one line, unchanged", titleBounds.height, chapterBounds.height)
        assertEquals("one line, unchanged", titleBounds.top, chapterBounds.top)
        assertTrue(
            "title still precedes the chapter",
            titleBounds.right <= chapterBounds.left,
        )
    }

    @Test
    fun `empty chapter renders title only`() {
        composeTestRule.setContent {
            RecallyTheme {
                ChapterHeader(bookId = 1, book = SHORT_TITLE, chapter = "")
            }
        }

        composeTestRule.onNodeWithText(SHORT_TITLE).assertIsDisplayed()
        composeTestRule.onNode(hasText("·", substring = true)).assertDoesNotExist()
    }

    /**
     * Real theme with `labelLarge` scaled up so real strings overflow. The
     * nested [MaterialTheme] re-provides only the typography (LocalTypography
     * is internal to material3); the header reads its colours from
     * [dev.recally.ui.theme.recallyColors], a separate composition local the
     * outer [RecallyTheme] still provides.
     */
    @Composable
    private fun GiantTypeTheme(content: @Composable () -> Unit) {
        RecallyTheme {
            MaterialTheme(typography = GiantTypography) {
                content()
            }
        }
    }

    private companion object {
        const val GIANT_SP = 100

        val GiantTypography =
            Typography(
                labelLarge = TextStyle(fontSize = GIANT_SP.sp, lineHeight = (GIANT_SP + 20).sp),
            )

        // The real header from issue #146, extended so the title alone is
        // wider than the row once the type scale is applied.
        const val LONG_TITLE =
            "Building Generative AI Services with FastAPI: " +
                "From Prototype to Production in the Enterprise"
        const val LONG_CHAPTER =
            "2. Getting Started with FastAPI and Serving Your First Generative Model Endpoint"
        const val SHORT_TITLE = "Evals for AI Engineers"
        const val SHORT_CHAPTER = "1. Introduction"
    }
}
