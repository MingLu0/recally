package dev.recally.ui.screens.stats

import dev.recally.domain.model.ForecastDay
import dev.recally.domain.model.Stats
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.StatsRepository
import dev.recally.ui.theme.DarkRecallyColorTokens
import dev.recally.ui.theme.LightRecallyColorTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The forecast chart's focal-bar rule (design-system.md, "Colour":
 * `primary-muted` is "Non-focal data marks — forecast chart's non-today
 * bars"). Composable-adjacent unit test, no Hilt graph: the colour pick is a
 * pure function of `isToday` and the theme tokens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForecastChartTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun test_todays_bar_is_the_only_focal_bar() =
        runTest {
            val sevenDays =
                (0L..6L).map { ForecastDay(date = TODAY.plusDays(it).toString(), due = (it + 1).toInt()) }
            val viewModel =
                StatsViewModel(
                    FakeStatsRepository(Result.Success(statsWithForecast(sevenDays))),
                    testDispatcher,
                    FIXED_CLOCK,
                )
            advanceUntilIdle()

            val forecast = viewModel.uiState.value.forecast
            assertEquals(7, forecast.size)
            assertEquals(
                "exactly one bar is focal",
                listOf(TODAY),
                forecast.filter { it.isToday }.map { it.date },
            )
            for (tokens in listOf(LightRecallyColorTokens, DarkRecallyColorTokens)) {
                assertEquals(
                    "today's bar uses primary",
                    tokens.primary,
                    forecastBarColor(isToday = true, colors = tokens),
                )
                assertEquals(
                    "the other six use primary-muted",
                    tokens.primaryMuted,
                    forecastBarColor(isToday = false, colors = tokens),
                )
            }
        }

    private class FakeStatsRepository(
        private val result: Result<Stats>,
    ) : StatsRepository {
        override suspend fun stats(): Result<Stats> = result
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 7)
        val FIXED_CLOCK: Clock = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

        fun statsWithForecast(forecast: List<ForecastDay>): Stats =
            Stats(
                streakDays = 0,
                reviewsToday = 0,
                retention30d = 0.0,
                lapseRateByType = emptyMap(),
                lapseRateByGuidanceVersion = emptyMap(),
                curationYield = 0.0,
                forecast = forecast,
            )
    }
}
