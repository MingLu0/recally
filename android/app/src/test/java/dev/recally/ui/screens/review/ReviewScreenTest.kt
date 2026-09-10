package dev.recally.ui.screens.review

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review card-face tap (issue #179). The front says "Tap to reveal the
 * answer", so the face has to be tappable — and only while unflipped: after
 * the reveal the rating row is the next decision, and a tap that flipped back
 * would both hide the answer and muddy the flip-to-rate `response_ms`
 * (docs/android.md:158, docs/design/design-system.md:233).
 *
 * Robolectric + createComposeRule as in ChapterHeaderTest/TodayScreenTest;
 * @GraphicsMode(NATIVE) so the card's text has real bounds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun test_card_face_is_clickable_only_while_unflipped() {
        composeTestRule.setContent {
            RecallyTheme {
                ReviewScreenUnderTest(uiState = frontState(), onFlip = {})
            }
        }

        composeTestRule
            .onNodeWithContentDescription(REVEAL_LABEL)
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun test_card_face_carries_no_click_action_once_flipped() {
        composeTestRule.setContent {
            RecallyTheme {
                ReviewScreenUnderTest(uiState = flippedState(), onFlip = {})
            }
        }

        // The reveal affordance is gone with the answer on screen, and the
        // card body it lived in must not have inherited its click action.
        composeTestRule.onNodeWithContentDescription(REVEAL_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(ANSWER_TEXT).assertHasNoClickAction()
    }

    @Test
    fun test_tapping_the_card_face_flips_it() {
        var flipCount = 0
        composeTestRule.setContent {
            RecallyTheme {
                ReviewScreenUnderTest(uiState = frontState(), onFlip = { flipCount++ })
            }
        }

        composeTestRule.onNodeWithContentDescription(REVEAL_LABEL).performClick()

        assertEquals("the card face routes through the same onFlip", 1, flipCount)
    }

    /** The button stays (issue #179 scope): both paths call the same onFlip. */
    @Test
    fun test_show_answer_button_still_flips_the_card() {
        var flipCount = 0
        composeTestRule.setContent {
            RecallyTheme {
                ReviewScreenUnderTest(uiState = frontState(), onFlip = { flipCount++ })
            }
        }

        composeTestRule.onNodeWithText("Show answer").performClick()

        assertEquals(1, flipCount)
    }

    /** The reveal icon must name itself; a null contentDescription hid it. */
    @Test
    fun test_reveal_affordance_is_labelled_for_accessibility() {
        composeTestRule.setContent {
            RecallyTheme {
                ReviewScreenUnderTest(uiState = frontState(), onFlip = {})
            }
        }

        val node = composeTestRule.onNodeWithContentDescription(REVEAL_LABEL).fetchSemanticsNode()
        assertEquals(
            "the label belongs to the tap target itself",
            true,
            node.config.contains(SemanticsActions.OnClick),
        )
    }

    // --- fixtures ---

    @Composable
    private fun ReviewScreenUnderTest(
        uiState: ReviewUiState,
        onFlip: () -> Unit,
    ) = ReviewScreen(
        uiState = uiState,
        onFlip = onFlip,
        onRate = {},
        onBury = {},
        onStartEdit = {},
        onDismissEdit = {},
        onEditCard = { _, _ -> },
        onClose = {},
        onOpenSettings = {},
        onRetry = {},
        onDone = {},
    )

    private fun frontState() =
        ReviewUiState(
            isLoading = false,
            card = testCard,
            doneCount = 1,
            toRepeatCount = 0,
            cardsLeft = 4,
        )

    private fun flippedState() =
        ReviewUiState(
            isLoading = false,
            card = testCard,
            isFlipped = true,
            answer = ANSWER_TEXT,
            ratingHints = RatingHints(again = "<1m", hard = "10m", good = null, easy = null),
            doneCount = 1,
            toRepeatCount = 0,
            cardsLeft = 4,
        )

    private companion object {
        const val REVEAL_LABEL = "Reveal the answer"
        const val ANSWER_TEXT = "An LLM pipeline's behavior only makes sense end-to-end."

        val testCard =
            ReviewCardUi(
                id = 101,
                type = "qa",
                front = "Why evaluate traces rather than individual steps?",
                bookId = 1,
                book = "Evals for AI Engineers",
                chapter = "3. Error Analysis",
            )
    }
}
