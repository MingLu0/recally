package dev.recally.ui.screens.stats

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import dev.recally.ui.components.SHIMMER_TAG
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
                    onRefresh = {},
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
                    onRefresh = {},
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
                    onRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText("87%").assertIsDisplayed()
    }

    @Test
    fun test_confident_sample_is_labelled_retention_30d() {
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState = loadedState(retention30d = 0.87, retentionReviewCount = 143),
                    onRetry = {},
                    onOpenSettings = {},
                    onRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText("retention 30d").assertIsDisplayed()
    }

    @Test
    fun test_no_qualifier_line_on_a_confident_sample() {
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState = loadedState(retention30d = 0.87, retentionReviewCount = 143),
                    onRetry = {},
                    onOpenSettings = {},
                    onRefresh = {},
                )
            }
        }

        composeTestRule
            .onAllNodesWithText("cards you'd already learned", substring = true)
            .fetchSemanticsNodes()
            .let { assertEquals("no qualifier line on a confident sample", 0, it.size) }
    }

    @Test
    fun test_small_sample_still_says_too_few_to_read() {
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState = loadedState(retention30d = 0.67, retentionReviewCount = 3),
                    onRetry = {},
                    onOpenSettings = {},
                    onRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText("from 3 reviews · too few to read").assertIsDisplayed()
    }

    @Test
    fun test_null_retention_renders_the_no_data_dash() {
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState = loadedState(retention30d = null),
                    onRetry = {},
                    onOpenSettings = {},
                    onRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText(NO_DATA).assertIsDisplayed()
    }

    @Test
    fun test_loading_stat_sections_use_shimmer() {
        // Issue #344: the three Stats loading sections (streak strip, forecast
        // chart, lapse-rate list) carry the shared shimmer, so the screen
        // reads as loading rather than rendered-but-empty. The animation
        // itself is gated in ShimmerTest.
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState = StatsUiState(isLoading = true),
                    onRetry = {},
                    onOpenSettings = {},
                    onRefresh = {},
                )
            }
        }

        composeTestRule.onAllNodesWithTag(SHIMMER_TAG).assertCountEquals(3)
    }

    /**
     * Issue #346: pulling down on Stats invokes its refresh callback exactly
     * once over loaded content.
     */
    @Test
    fun test_pull_down_invokes_stats_refresh_exactly_once() {
        val refreshCalls = mutableListOf<Unit>()
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState = loadedState(retention30d = 0.87),
                    onRetry = {},
                    onOpenSettings = {},
                    onRefresh = { refreshCalls += Unit },
                )
            }
        }

        pullDownToRefresh()

        assertEquals("one downward pull triggers exactly one refresh", 1, refreshCalls.size)
    }

    /**
     * Issue #346: the error state's copy says "Something went wrong — pull to
     * retry." — the promised gesture must actually work there.
     */
    @Test
    fun test_stats_error_state_supports_pull_to_refresh() {
        val refreshCalls = mutableListOf<Unit>()
        composeTestRule.setContent {
            RecallyTheme {
                StatsScreen(
                    uiState =
                        StatsUiState(
                            isLoading = false,
                            errorMessage = "Something went wrong — pull to retry.",
                        ),
                    onRetry = {},
                    onOpenSettings = {},
                    onRefresh = { refreshCalls += Unit },
                )
            }
        }

        // The retry copy is on screen, so the assertion below is about the
        // gesture honouring it, not about an error state rendering nothing.
        composeTestRule.onNodeWithText("Something went wrong — pull to retry.").assertIsDisplayed()

        pullDownToRefresh()

        assertEquals("the error state still refreshes on pull", 1, refreshCalls.size)
    }

    /**
     * One downward pull released past the 80dp trigger threshold, as a user
     * dragging the screen down would produce.
     */
    private fun pullDownToRefresh() {
        composeTestRule.onRoot().performTouchInput {
            swipe(center, center + Offset(0f, 400f), durationMillis = 300)
        }
        composeTestRule.waitForIdle()
    }

    private fun loadedState(
        retention30d: Double?,
        retentionReviewCount: Int = if (retention30d == null) 0 else 120,
    ): StatsUiState {
        val today = LocalDate.of(2026, 9, 9)
        return StatsUiState(
            streakDays = 9,
            reviewsToday = 23,
            retention30d = retention30d,
            retentionReviewCount = retentionReviewCount,
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
