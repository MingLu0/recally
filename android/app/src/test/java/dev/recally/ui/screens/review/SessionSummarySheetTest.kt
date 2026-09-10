package dev.recally.ui.screens.review

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.recally.ui.theme.RecallyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pluralisation gate for issue #149: a one-card session used to summarise as
 * "1 cards in …". English zero takes the plural ("0 cards"), so only
 * exactly-one flips — matching `HighlightDisclosure.kt`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionSummarySheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `one-card session reads 1 card`() {
        setSheet(reviewedCount = 1)

        composeTestRule.onNodeWithText("1 card in 1 min 11 s").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1 cards", substring = true).assertCountEquals(0)
    }

    /** Regression: zero keeps the plural ("0 cards"). */
    @Test
    fun `plural form is unchanged at zero`() {
        setSheet(reviewedCount = 0)

        composeTestRule.onNodeWithText("0 cards in 1 min 11 s").assertIsDisplayed()
    }

    /** Regression: N > 1 keeps the plural. */
    @Test
    fun `plural form is unchanged above one`() {
        setSheet(reviewedCount = 12)

        composeTestRule.onNodeWithText("12 cards in 1 min 11 s").assertIsDisplayed()
    }

    private fun setSheet(reviewedCount: Int) {
        composeTestRule.setContent {
            RecallyTheme {
                SessionSummarySheet(
                    summary =
                        SessionSummaryUi(
                            reviewedCount = reviewedCount,
                            elapsedMs = 71_000,
                            goodOrEasyCount = reviewedCount,
                            hardCount = 0,
                            againCount = 0,
                            lapseCount = 0,
                        ),
                    onDone = {},
                )
            }
        }
    }
}
