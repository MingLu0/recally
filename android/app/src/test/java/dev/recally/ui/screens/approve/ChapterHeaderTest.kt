package dev.recally.ui.screens.approve

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.width
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Chapter group header layout (issue #201): the book title and chapter now
 * render on two lines instead of sharing one line and both being squeezed to
 * an unreadable stub — the one-line layout from issue #146 traded wrapping
 * for truncation, and on a long title + long chapter pair both hit the
 * ellipsis. `docs/design/design-system.md`, "Chapter group header" now
 * specifies the two-line form, superseding #146.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChapterHeaderTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun test_book_title_and_chapter_render_on_separate_lines() {
        composeTestRule.setContent {
            RecallyTheme {
                ChapterHeader(bookId = 1, book = LONG_TITLE, chapter = LONG_CHAPTER)
            }
        }

        val titleBounds = composeTestRule.onNodeWithText(LONG_TITLE).getBoundsInRoot()
        val chapterBounds = composeTestRule.onNodeWithText(LONG_CHAPTER).getBoundsInRoot()

        assertTrue(
            "title and chapter occupy different vertical positions",
            titleBounds.top != chapterBounds.top,
        )
    }

    @Test
    fun test_long_book_title_is_not_squeezed_by_the_chapter() {
        // Both layouts rendered in one composition (setContent can only be
        // called once per test) so their title widths can be compared
        // directly. The old one-line layout is pinned here exactly as it was
        // in ChapterHeader.kt before this change: title on
        // weight(1f, fill = false) competing with the chapter on weight(1f).
        composeTestRule.setContent {
            RecallyTheme {
                Column {
                    OneLineChapterHeaderUnderTest(book = REAL_TITLE, chapter = REAL_CHAPTER)
                    ChapterHeader(bookId = 1, book = REAL_TITLE, chapter = REAL_CHAPTER)
                }
            }
        }

        val oldLayoutTitleWidth =
            composeTestRule.onNodeWithTag(OLD_LAYOUT_TITLE_TAG).getBoundsInRoot().width
        val titleNodes = composeTestRule.onAllNodesWithText(REAL_TITLE)
        // Two titles render with the same text: the pinned old layout (tagged
        // above, excluded here) and the ChapterHeader under test.
        val newLayoutTitleWidth = titleNodes[1].getBoundsInRoot().width

        assertTrue(
            "title is no longer squeezed by the chapter — it measures wider on its own line " +
                "(old: $oldLayoutTitleWidth, new: $newLayoutTitleWidth)",
            newLayoutTitleWidth > oldLayoutTitleWidth,
        )
    }

    /** #146's one-line layout, pinned here only so this test can measure it. */
    @Composable
    private fun OneLineChapterHeaderUnderTest(
        book: String,
        chapter: String,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
        ) {
            Text(
                text = book,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.recallyColors.ink,
                modifier = Modifier.weight(1f, fill = false).testTag(OLD_LAYOUT_TITLE_TAG),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chapter.isNotEmpty()) {
                Text(
                    text = "· $chapter",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.recallyColors.inkFaint,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Test
    fun test_chapter_has_no_leading_separator() {
        composeTestRule.setContent {
            RecallyTheme {
                ChapterHeader(bookId = 1, book = SHORT_TITLE, chapter = SHORT_CHAPTER)
            }
        }

        composeTestRule.onNode(hasText("· $SHORT_CHAPTER")).assertDoesNotExist()
        val chapterNode = composeTestRule.onNodeWithText(SHORT_CHAPTER)
        chapterNode.assertIsDisplayed()
        assertFalse(
            "chapter text does not start with a leading separator",
            SHORT_CHAPTER.trimStart().startsWith("·"),
        )
    }

    @Test
    fun test_empty_chapter_renders_title_only() {
        composeTestRule.setContent {
            RecallyTheme {
                ChapterHeader(bookId = 1, book = SHORT_TITLE, chapter = "")
            }
        }

        composeTestRule.onNodeWithText(SHORT_TITLE).assertIsDisplayed()
        composeTestRule.onNode(hasText("·", substring = true)).assertDoesNotExist()
    }

    @Test
    fun test_spine_is_vertically_centred_against_both_lines() {
        composeTestRule.setContent {
            RecallyTheme {
                ChapterHeader(bookId = 1, book = SHORT_TITLE, chapter = SHORT_CHAPTER)
            }
        }

        val titleBounds = composeTestRule.onNodeWithText(SHORT_TITLE).getBoundsInRoot()
        val chapterBounds = composeTestRule.onNodeWithText(SHORT_CHAPTER).getBoundsInRoot()
        val spineNode = composeTestRule.onNodeWithTag(SPINE_TEST_TAG)
        val spineBounds = spineNode.getBoundsInRoot()

        val textColumnTop = minOf(titleBounds.top, chapterBounds.top)
        val textColumnBottom = maxOf(titleBounds.bottom, chapterBounds.bottom)
        val textColumnCenterY = (textColumnTop + textColumnBottom) / 2
        val spineCenterY = (spineBounds.top + spineBounds.bottom) / 2

        assertTrue(
            "spine is vertically centred against the text column",
            abs(spineCenterY.value - textColumnCenterY.value) <= 1f,
        )
    }

    private companion object {
        const val LONG_TITLE =
            "Building Generative AI Services with FastAPI: " +
                "From Prototype to Production in the Enterprise"
        const val LONG_CHAPTER =
            "2. Getting Started with FastAPI and Serving Your First Generative Model Endpoint"
        const val SHORT_TITLE = "Evals for AI Engineers"
        const val SHORT_CHAPTER = "1. Introduction"

        // The exact pair from the issue report.
        const val REAL_TITLE = "Building Generative AI Services with FastAPI"
        const val REAL_CHAPTER = "2. Getting Started with FastAPI"

        const val SPINE_TEST_TAG = "chapter_header_spine"
        const val OLD_LAYOUT_TITLE_TAG = "old_layout_title"
    }
}
