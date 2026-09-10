package dev.recally.ui.screens.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The Stats headline retention figure (issue #190). `retention_30d` is
 * nullable on `GET /stats` — null means "no reviews in the window", which is
 * not the same statement as 0% and must never render as one
 * (design-system.md, "States" → "No data for a metric").
 *
 * Robolectric + createComposeRule, as in TodayScreenTest — never a real
 * backend. @GraphicsMode(NATIVE) for real text measurement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun test_retention_renders_the_no_data_treatment_when_null() {
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState = loadedState(retention30d = null),
                    onRetry = {},
                    onOpenSettings = {},
                )
            }
        }

        composeTestRule.onNodeWithText(NO_DATA).assertIsDisplayed()
    }

    @Test
    fun test_retention_never_renders_zero_percent_for_no_data() {
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState = loadedState(retention30d = null),
                    onRetry = {},
                    onOpenSettings = {},
                )
            }
        }

        // Nothing anywhere on the screen reads "0%": a null window is an
        // absence, and 0% is the worse of the two readings.
        composeTestRule.onAllNodesWithText("0%").fetchSemanticsNodes().let {
            assertEquals("no node renders 0% for a null retention", 0, it.size)
        }
    }

    @Test
    fun test_retention_renders_a_percentage_when_present() {
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState = loadedState(retention30d = 0.87),
                    onRetry = {},
                    onOpenSettings = {},
                )
            }
        }

        composeTestRule.onNodeWithText("87%").assertIsDisplayed()
    }

    private fun loadedState(retention30d: Double?): StatsUiState {
        val today = LocalDate.of(2026, 9, 9)
        return StatsUiState(
            streakDays = 9,
            reviewsToday = 23,
            retention30d = retention30d,
            retentionReviewCount = if (retention30d == null) 0 else 120,
            forecast =
                (0L..6L).map { offset ->
                    ForecastBar(date = today.plusDays(offset), due = 3, isToday = offset == 0L)
                },
            lapseRateByType = mapOf("qa" to 0.11, "cloze" to 0.18),
        )
    }

    private companion object {
        /** The no-data treatment from design-system.md, "States". */
        const val NO_DATA = "–"
    }
}
