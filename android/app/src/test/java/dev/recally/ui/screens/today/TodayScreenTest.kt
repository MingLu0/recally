package dev.recally.ui.screens.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.recally.domain.model.Deck
import dev.recally.ui.theme.RecallyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Today "Your books" rail (issue #154, artboard `docs/design/RcWhite.dc.html`):
 * the section renders below the approval row with one row per book from
 * `GET /decks`. Robolectric + createComposeRule, as in ChapterHeaderTest —
 * never a real backend. @GraphicsMode(NATIVE) for real text measurement:
 * the legacy shadow layout gives the wrapped book title no real bounds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun test_your_books_section_is_displayed() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = listOf(sampleDeck())),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Your books").assertIsDisplayed()
        composeTestRule.onNodeWithText("From your O'Reilly highlights").assertIsDisplayed()
        composeTestRule.onNodeWithText("Evals for AI Engineers").assertIsDisplayed()
    }

    @Test
    fun test_book_row_shows_card_count_and_progress() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = listOf(sampleDeck())),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("8 cards").assertIsDisplayed()
        // 0.875 renders as 88% — rounded, never truncated to 87.
        composeTestRule.onNodeWithText("88%").assertIsDisplayed()
    }

    private companion object {
        fun sampleDeck(): Deck = Deck(bookId = 2, title = "Evals for AI Engineers", total = 8, due = 1, progress = 0.875f)

        fun loadedState(books: List<Deck>): TodayUiState =
            TodayUiState(
                isLoading = false,
                dueCount = 3,
                newCount = 1,
                streakDays = 9,
                reviewsToday = 23,
                retention30d = 0.87,
                books = books,
            )
    }
}
