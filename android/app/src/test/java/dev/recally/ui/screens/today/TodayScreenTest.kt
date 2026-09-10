package dev.recally.ui.screens.today

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.recally.domain.model.Deck
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
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
                    onBookClick = {},
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
                    onBookClick = {},
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
                    onBookClick = {},
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
                    onBookClick = {},
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
                    onBookClick = {},
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
                    onBookClick = {},
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
                    onBookClick = {},
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
                    onBookClick = {},
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
                    onBookClick = {},
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
                    onBookClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("0 cards").assertIsDisplayed()
    }

    @Test
    fun test_tapping_a_book_emits_its_book_id() {
        val clickedBookIds = mutableListOf<Long>()
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = listOf(firstDeck(), secondDeck())),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                    onBookClick = { bookId -> clickedBookIds += bookId },
                )
            }
        }

        // The second book, not the first: a rail that always emitted
        // books.first() would pass a single-book test.
        composeTestRule.onNodeWithText("30 Agents in 30 Days").performClick()

        assertEquals(listOf(secondDeck().bookId), clickedBookIds)
    }

    @Test
    fun test_book_click_is_not_emitted_on_section_chrome() {
        val clickedBookIds = mutableListOf<Long>()
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = listOf(firstDeck(), secondDeck())),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                    onBookClick = { bookId -> clickedBookIds += bookId },
                )
            }
        }

        // Negative: the click target is the card, never the section heading.
        // A clickable wrapped around the whole rail Column would open a book
        // from the heading -- and pick an arbitrary one.
        composeTestRule.onNodeWithText("Your books").performClick()
        composeTestRule.onNodeWithText("From your O'Reilly highlights").performClick()

        assertEquals(emptyList<Long>(), clickedBookIds)
    }

    @Test
    fun test_books_rail_renders_a_card_per_book() {
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = listOf(firstDeck(), secondDeck())),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                    onBookClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Your books").assertIsDisplayed()
        composeTestRule.onNodeWithText("Evals for AI Engineers").assertIsDisplayed()
        composeTestRule.onNodeWithText("30 Agents in 30 Days").assertIsDisplayed()
    }

    @Test
    fun test_books_rail_shows_its_failure_state_when_decks_did_not_load() {
        // Issue #189: a rail that failed to load must say so, not fall silent.
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = emptyList()).copy(booksFailedToLoad = true),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                    onBookClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Your books").assertIsDisplayed()
        composeTestRule.onNodeWithText(BOOKS_FAILURE_TEXT).assertIsDisplayed()
    }

    @Test
    fun test_books_rail_is_absent_only_for_a_genuinely_empty_library() {
        // Loaded-and-empty draws nothing — no header over an empty rail
        // (design-system.md, "Your books rail").
        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = loadedState(books = emptyList()),
                    onStartReview = {},
                    onOpenApprove = {},
                    onOpenSettings = {},
                    onRetry = {},
                    onBookClick = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText("Your books").assertCountEquals(0)
        composeTestRule.onAllNodesWithText(BOOKS_FAILURE_TEXT).assertCountEquals(0)
    }

    private companion object {
        /** The rail's failure strip (design-system.md, "Your books rail"). */
        const val BOOKS_FAILURE_TEXT = "Couldn't load your books"

        fun sampleDeck(): Deck =
            Deck(bookId = 2, title = "Evals for AI Engineers", total = 8, due = 1, progress = 0.875f, chapters = 3, truncated = 0)

        fun firstDeck(): Deck =
            Deck(bookId = 7, title = "Evals for AI Engineers", total = 8, due = 1, progress = 0.875f, chapters = 3, truncated = 0)

        fun secondDeck(): Deck =
            Deck(bookId = 11, title = "30 Agents in 30 Days", total = 83, due = 0, progress = 0.24f, chapters = 12, truncated = 0)

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
