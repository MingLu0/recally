package dev.recally.ui.screens.decks

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
 * Pluralisation gate for issue #149: the deck list used to concatenate a
 * hard-coded plural ("1 cards", "1 books"). The singular follows the pattern
 * already in `HighlightDisclosure.kt` ("source highlight" / "…s"). English
 * zero takes the plural ("0 cards"), so only exactly-one flips.
 *
 * @GraphicsMode(NATIVE) for real text measurement, as in BookDetailScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecksScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `single card deck reads 1 card`() {
        setScreen(decks = listOf(deck(total = 1, chapters = 1)))

        // Both halves of the row subtitle pluralise (chapters added in #172).
        composeTestRule.onNodeWithText("1 card · 1 chapter").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1 cards", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("1 chapters", substring = true).assertCountEquals(0)
    }

    @Test
    fun `header uses singular for one book`() {
        setScreen(decks = listOf(deck(total = 1, chapters = 1)))

        composeTestRule.onNodeWithText("1 card · 1 book").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1 books").assertCountEquals(0)
    }

    /** Regression: zero and N > 1 keep the plural on the row and the header. */
    @Test
    fun `plural forms are unchanged at zero and above one`() {
        setScreen(
            decks =
                listOf(
                    deck(bookId = 1, total = 0, chapters = 0),
                    deck(bookId = 2, total = 8, chapters = 9),
                ),
        )

        // A book with no chapters yet shows the card count alone (issue #172),
        // never a bare "0 chapters".
        composeTestRule.onNodeWithText("0 cards").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("0 chapters", substring = true).assertCountEquals(0)
        composeTestRule.onNodeWithText("8 cards · 9 chapters").assertIsDisplayed()
        composeTestRule.onNodeWithText("8 cards · 2 books").assertIsDisplayed()
    }

    private fun setScreen(decks: List<Deck>) {
        composeTestRule.setContent {
            RecallyTheme {
                DecksScreen(
                    uiState = DecksUiState(decks = decks),
                    onDeckClick = {},
                    onRetry = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    private fun deck(
        bookId: Long = 1,
        total: Int,
        chapters: Int = 3,
    ) = Deck(
        bookId = bookId,
        title = "Book $bookId",
        total = total,
        due = 0,
        progress = 0f,
        chapters = chapters,
    )
}
