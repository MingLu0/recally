package dev.recally.ui.screens.today

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    @Test
    fun test_waiting_for_you_section_heading_is_displayed() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(listOf(sampleDeck())).copy(pendingReviewCount = 8, needsHumanCount = 3),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Waiting for you").assertIsDisplayed()
    }

    @Test
    fun test_queue_tiles_show_the_number_apart_from_its_label() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(listOf(sampleDeck())).copy(pendingReviewCount = 8, needsHumanCount = 3),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        // The artboard stacks the metric over its label, so each is its own
        // node -- "8" and "to approve", never the fused "8 to approve" chip.
        composeTestRule.onNodeWithText("8").assertIsDisplayed()
        composeTestRule.onNodeWithText("to approve").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
        composeTestRule.onNodeWithText("need you").assertIsDisplayed()
    }

    @Test
    fun test_queue_tiles_are_not_a_fused_count_label_chip() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(listOf(sampleDeck())).copy(pendingReviewCount = 8, needsHumanCount = 3),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        // Negative: the pre-#132 chip rendered one fused string. Colour on
        // numbers, not on chrome (design-system.md, "Rules") needs them split.
        composeTestRule.onAllNodesWithText("8 to approve").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("3 need you").assertCountEquals(0)
    }

    @Test
    fun test_needs_you_tile_is_absent_when_nothing_needs_you() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(listOf(sampleDeck())).copy(pendingReviewCount = 8, needsHumanCount = 0),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("to approve").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("need you").assertCountEquals(0)
    }

    @Test
    fun test_waiting_for_you_section_is_absent_while_counts_are_unknown() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(listOf(sampleDeck())),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        // Negative: the queue needs connectivity, so Today must render before
        // it answers -- no empty section header with no numbers under it.
        composeTestRule.onAllNodesWithText("Waiting for you").assertCountEquals(0)
    }

    @Test
    fun test_single_card_book_reads_one_card() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = listOf(sampleDeck().copy(total = 1))),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("1 card").assertIsDisplayed()
        // Negative: the rail was added after #149 fixed the other five sites.
        composeTestRule.onAllNodesWithText("1 cards").assertCountEquals(0)
    }

    @Test
    fun test_multi_card_book_keeps_the_plural() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = listOf(sampleDeck().copy(total = 8))),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("8 cards").assertIsDisplayed()
    }

    @Test
    fun test_zero_card_book_uses_the_plural() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = listOf(sampleDeck().copy(total = 0))),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText("0 cards").assertIsDisplayed()
    }

    private companion object {
        fun sampleDeck(): Deck = Deck(bookId = 2, title = "Evals for AI Engineers", total = 8, due = 1, progress = 0.875f, chapters = 3)

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
