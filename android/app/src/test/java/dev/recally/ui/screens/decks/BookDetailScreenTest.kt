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
import java.time.Instant
import java.time.ZoneOffset

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

    /**
     * Pluralisation gate for issue #149: a one-card chapter used to read
     * "1 cards".
     */
    @Test
    fun `single card chapter reads 1 card`() {
        setDetailContent(
            DecksUiState(
                isBookDetail = true,
                bookId = 4,
                bookTitle = "Short Book",
                bookDue = 1,
                chapters = listOf(ChapterSummary(name = "1. Only Chapter", cardCount = 1)),
            ),
        )

        composeTestRule.onNodeWithText("1 card").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1 cards", substring = true).assertCountEquals(0)
        // The header subtitle pluralises the same way (post-#152 shape).
        composeTestRule.onNodeWithText("1 card · 1 due").assertIsDisplayed()
    }

    /** Regression: zero and N > 1 keep the plural on chapters and subtitle. */
    @Test
    fun `plural forms are unchanged at zero and above one`() {
        setDetailContent(
            DecksUiState(
                isBookDetail = true,
                bookId = 3,
                bookTitle = "Zero and Many",
                bookDue = 2,
                chapters =
                    listOf(
                        ChapterSummary(name = "1. Empty", cardCount = 0),
                        ChapterSummary(name = "2. Busy", cardCount = 8),
                    ),
            ),
        )

        composeTestRule.onNodeWithText("0 cards").assertIsDisplayed()
        composeTestRule.onNodeWithText("8 cards").assertIsDisplayed()
        composeTestRule.onNodeWithText("8 cards · 2 due").assertIsDisplayed()
    }

    /**
     * G5 gate (issue #172), **negative**: the due label is the server's
     * `card_state.due` rendered, never a date the client invented. A card whose
     * response carries no `due` — a fresh card at `learning` step 0 — shows no
     * date at all rather than one derived from its state (hard rule 5, ADR-005).
     */
    @Test
    fun `card row shows state and due from the server`() {
        val scheduledDue = Instant.parse("2026-09-09T08:00:00Z")
        setDetailContent(
            DecksUiState(
                isBookDetail = true,
                bookId = 2,
                bookTitle = "Evals for AI Engineers",
                chapters = listOf(ChapterSummary(name = "3. Error Analysis", cardCount = 2)),
                expandedChapter = "3. Error Analysis",
                expandedCards =
                    listOf(
                        card(1, state = "review", due = scheduledDue),
                        card(2, state = "learning", due = null),
                    ),
            ),
        )

        // The label is a pure function of the server's instant: with `now` fixed
        // days earlier, the rendered text is that date and nothing else. No
        // client-side arithmetic invents it (hard rule 5).
        val fixedNow = Instant.parse("2026-09-04T08:00:00Z")
        assertEquals(
            "9 Sep",
            deckCardDueLabel(due = scheduledDue, now = fixedNow, zone = ZoneOffset.UTC),
        )
        // The screen shows a label for the scheduled card…
        composeTestRule.onAllNodesWithText("Due", substring = true).assertCountEquals(1)
        // …and the unscheduled card is labelled by its state, with no date at all.
        composeTestRule.onNodeWithText("LEARNING").assertIsDisplayed()
        assertEquals(
            "no `due` from the server means no date on screen",
            null,
            deckCardDueLabel(due = null, now = fixedNow, zone = ZoneOffset.UTC),
        )
    }

    /**
     * G5 gate (issue #172): the server orders `GET /decks/{book_id}/cards` by
     * chapter then `export_position`, and the client's grouping must not reorder
     * it — a book's chapters are not alphabetical, so a sort here would scramble
     * the reading order.
     */
    @Test
    fun `chapter grouping reproduces export position order`() {
        val serverOrder =
            listOf("10. Scaling", "2. Getting Started", "Appendix A", "1. Introduction")
        setDetailContent(
            DecksUiState(
                isBookDetail = true,
                bookId = 2,
                bookTitle = "Evals for AI Engineers",
                chapters = serverOrder.map { ChapterSummary(name = it, cardCount = 1) },
            ),
        )

        val rendered =
            serverOrder.map { name ->
                composeTestRule.onNodeWithText(name).assertIsDisplayed()
                composeTestRule
                    .onNodeWithText(name)
                    .fetchSemanticsNode()
                    .positionInRoot.y
            }
        assertEquals(rendered.sorted(), rendered)
    }

    private fun setDetailContent(uiState: DecksUiState) {
        composeTestRule.setContent {
            RecallyTheme {
                BookDetailScreen(
                    uiState = uiState,
                    onBack = {},
                    onChapterToggled = {},
                    onStartEdit = {},
                    onDismissEdit = {},
                    onSubmitEdit = { _, _ -> },
                    onSuspendCard = {},
                    onUnsuspendCard = {},
                    onRetry = {},
                    onOpenSettings = {},
                )
            }
        }
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

    private fun card(
        id: Long,
        state: String = "review",
        due: Instant? = Instant.parse("2026-09-09T08:00:00Z"),
    ) = DeckCard(
        id = id,
        type = "qa",
        front = "Why evaluate traces rather than individual steps? ($id)",
        back = "An LLM pipeline's behavior only makes sense end-to-end.",
        chapter = "2. Getting Started with FastAPI",
        tags = emptyList(),
        suspendedUntil = null,
        state = state,
        due = due,
    )
}
