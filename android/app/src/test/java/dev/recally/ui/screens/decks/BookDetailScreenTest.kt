package dev.recally.ui.screens.decks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.recally.domain.model.DeckCard
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.bookCoverColor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Book-detail header gate for issue #152: the header renders the same spine
 * chip, progress % and due badge as the book's Decks row (the data comes from
 * `GET /decks`, already fetched for the title), and nothing on screen claims
 * "read-only" above the ADR-008 edit/suspend controls.
 *
 * The state is the real payload from the issue: book 2, 8 cards, due 1,
 * progress 0.875. @GraphicsMode(NATIVE) for real text measurement, as in
 * ChapterHeaderTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookDetailScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `header shows progress and due count`() {
        setScreen()

        composeTestRule.onNodeWithText("88%").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 DUE").assertIsDisplayed()
    }

    @Test
    fun `header shows the book spine chip`() {
        setScreen()

        composeTestRule.onNodeWithTag(BOOK_SPINE_TAG).assertIsDisplayed()
        // The chip carries the title initial, like the Decks row's cover.
        composeTestRule.onNodeWithText("B").assertIsDisplayed()
        // "The book's stable colour": book 2 maps to a fixed palette entry
        // (design-system.md, "Colour" — never reorder BookCoverColors), so
        // the spine matches its Decks row on every install.
        assertEquals(Color(0xFF24403A), bookCoverColor(2, darkTheme = false))
    }

    @Test
    fun `subtitle does not claim read-only`() {
        setScreen()

        composeTestRule
            .onAllNodesWithText("read-only", substring = true, ignoreCase = true)
            .assertCountEquals(0)
        // The accurate subtitle names the counts (issue #152).
        composeTestRule.onNodeWithText("8 cards · 1 due").assertIsDisplayed()
        // …while the ADR-008 controls it used to contradict stay enabled.
        composeTestRule.onAllNodesWithText("Edit")[0].assertIsEnabled()
        composeTestRule.onAllNodesWithText("Suspend")[0].assertIsEnabled()
    }

    /** Regression guard: the header rework must not disturb ADR-008. */
    @Test
    fun `card controls remain available`() {
        var editedCardId: Long? = null
        var suspendedCardId: Long? = null
        setScreen(
            onStartEdit = { editedCardId = it.id },
            onSuspendCard = { suspendedCardId = it },
        )

        composeTestRule.onAllNodesWithText("Edit")[0].performClick()
        assertEquals(1L, editedCardId)
        composeTestRule.onAllNodesWithText("Suspend")[0].performClick()
        assertEquals(1L, suspendedCardId)
    }

    private fun setScreen(
        onStartEdit: (DeckCard) -> Unit = {},
        onSuspendCard: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            RecallyTheme {
                BookDetailScreen(
                    uiState = detailState(),
                    onBack = {},
                    onChapterToggled = {},
                    onStartEdit = onStartEdit,
                    onDismissEdit = {},
                    onSubmitEdit = { _, _ -> },
                    onSuspendCard = onSuspendCard,
                    onUnsuspendCard = {},
                    onRetry = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    private fun detailState() =
        DecksUiState(
            isBookDetail = true,
            bookId = 2,
            bookTitle = "Building Generative AI Services with FastAPI",
            bookDue = 1,
            bookProgress = 0.875f,
            chapters =
                listOf(
                    ChapterSummary(name = "2. Getting Started with FastAPI", cardCount = 5),
                    ChapterSummary(name = "3. Services", cardCount = 3),
                ),
            expandedChapter = "2. Getting Started with FastAPI",
            expandedCards = listOf(card(1), card(2)),
        )

    private fun card(id: Long) =
        DeckCard(
            id = id,
            type = "qa",
            front = "Why evaluate traces rather than individual steps? ($id)",
            back = "An LLM pipeline's behavior only makes sense end-to-end.",
            chapter = "2. Getting Started with FastAPI",
            tags = emptyList(),
            suspendedUntil = null,
        )
}
