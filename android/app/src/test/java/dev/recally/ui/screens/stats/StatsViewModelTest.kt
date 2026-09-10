package dev.recally.ui.screens.stats

import dev.recally.domain.model.ForecastDay
import dev.recally.domain.model.Stats
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Stats ViewModel tests against a fake repository on a TestDispatcher
 * (docs/android.md, "Tests"). The step 6a-b gate (ADR-012): every figure maps
 * one-to-one onto `GET /stats`, the forecast is padded/truncated to the next
 * seven calendar days from today with absent days as zeros in position, the
 * guidance-version section hides below two versions and sorts numerically,
 * an empty database renders zeros rather than an error, offline surfaces no
 * stale numbers, a 401 surfaces the auth banner, and `next_due_at` maps into
 * the UiState (issue #134).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var statsRepository: FakeStatsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        statsRepository = FakeStatsRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun test_ui_state_maps_every_documented_stats_field() =
        runTest {
            statsRepository.result = Result.Success(fullStats())
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNull(state.errorMessage)
            assertEquals("streak_days", 9, state.streakDays)
            assertEquals("reviews_today", 23, state.reviewsToday)
            assertEquals("retention_30d", 0.87, state.retention30d!!, 0.0001)
            assertEquals("retention_30d_reviews", 120, state.retentionReviewCount)
            assertEquals("lapse_rate_by_type", mapOf("qa" to 0.11, "cloze" to 0.18), state.lapseRateByType)
            assertEquals(
                "lapse_rate_by_guidance_version, keys as strings sorted numerically",
                listOf(GuidanceVersionLapseRate(1, 0.19), GuidanceVersionLapseRate(2, 0.12)),
                state.lapseRateByGuidanceVersion,
            )
            assertEquals("forecast carries the served dues", 14, state.forecast[0].due)
            // curation_yield is deliberately absent from the UiState: it is in
            // the response but not on the artboard, and the ticket scopes it
            // out (issue #95, "Scoped out").
        }

    @Test
    fun test_forecast_is_padded_to_seven_days_with_zeros() =
        runTest {
            statsRepository.result =
                Result.Success(
                    statsWithForecast(
                        ForecastDay(date = TODAY.toString(), due = 5),
                        ForecastDay(date = TODAY.plusDays(1).toString(), due = 3),
                        ForecastDay(date = TODAY.plusDays(2).toString(), due = 8),
                    ),
                )
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()

            val forecast = viewModel.uiState.value.forecast
            assertEquals("a 3-entry forecast renders 7 bars", 7, forecast.size)
            assertEquals(listOf(5, 3, 8, 0, 0, 0, 0), forecast.map { it.due })
            assertEquals(
                "bars cover today and the next six calendar days",
                (0L..6L).map { TODAY.plusDays(it) },
                forecast.map { it.date },
            )
        }

    @Test
    fun test_forecast_longer_than_seven_days_is_truncated() =
        runTest {
            // 14 entries, due = offset + 1, so each day's value identifies it.
            val fourteenDays =
                (0L..13L)
                    .map { ForecastDay(date = TODAY.plusDays(it).toString(), due = (it + 1).toInt()) }
            statsRepository.result = Result.Success(statsWithForecast(*fourteenDays.toTypedArray()))
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()

            val forecast = viewModel.uiState.value.forecast
            assertEquals("a 14-entry forecast still renders 7 bars", 7, forecast.size)
            assertEquals("the chart starts today", TODAY, forecast[0].date)
            assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), forecast.map { it.due })
        }

    @Test
    fun test_forecast_days_absent_from_the_response_are_zero_not_skipped() =
        runTest {
            // Gaps at +1, +3 and +4: a chart that compacts the served entries
            // instead of placing them by date would shift 9 into position 1.
            statsRepository.result =
                Result.Success(
                    statsWithForecast(
                        ForecastDay(date = TODAY.toString(), due = 4),
                        ForecastDay(date = TODAY.plusDays(2).toString(), due = 9),
                        ForecastDay(date = TODAY.plusDays(5).toString(), due = 6),
                    ),
                )
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()

            val dues =
                viewModel.uiState.value.forecast
                    .map { it.due }
            assertEquals("the gap is a zero bar in position, not a shifted chart", listOf(4, 0, 9, 0, 0, 6, 0), dues)
        }

    @Test
    fun test_guidance_version_section_is_hidden_with_fewer_than_two_versions() =
        runTest {
            statsRepository.result = Result.Success(statsWithGuidanceVersions(emptyMap()))
            val emptyViewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()
            assertFalse(
                "an empty map hides the section (3e's empty-database shape)",
                emptyViewModel.uiState.value.showGuidanceVersionSection,
            )

            statsRepository.result = Result.Success(statsWithGuidanceVersions(mapOf("1" to 0.19)))
            val singleKeyViewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()
            assertFalse(
                "a single version hides the section (android.md: shown only with more than one)",
                singleKeyViewModel.uiState.value.showGuidanceVersionSection,
            )
        }

    @Test
    fun test_guidance_version_section_shows_with_two_or_more_versions() =
        runTest {
            statsRepository.result = Result.Success(statsWithGuidanceVersions(mapOf("1" to 0.19, "2" to 0.12)))
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.showGuidanceVersionSection)
            assertEquals(
                listOf(GuidanceVersionLapseRate(1, 0.19), GuidanceVersionLapseRate(2, 0.12)),
                state.lapseRateByGuidanceVersion,
            )
        }

    @Test
    fun test_guidance_versions_sort_numerically_not_lexically() =
        runTest {
            statsRepository.result = Result.Success(statsWithGuidanceVersions(mapOf("10" to 0.5, "2" to 0.3)))
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()

            assertEquals(
                "v2 sorts before v10 — lexical order would invert them",
                listOf(2, 10),
                viewModel.uiState.value.lapseRateByGuidanceVersion
                    .map { it.version },
            )
        }

    @Test
    fun test_empty_database_renders_zeros_not_an_error_state() =
        runTest {
            // 3e pins this shape: GET /stats on an empty database returns
            // zeros and an empty forecast rather than an error
            // (test_stats_on_empty_database_returns_zeros_not_errors).
            statsRepository.result =
                Result.Success(
                    Stats(
                        streakDays = 0,
                        reviewsToday = 0,
                        retention30d = null,
                        retention30dReviews = 0,
                        lapseRateByType = emptyMap(),
                        lapseRateByGuidanceVersion = emptyMap(),
                        curationYield = 0.0,
                        nextDueAt = null,
                        forecast = emptyList(),
                    ),
                )
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse("still loading", state.isLoading)
            assertNull("an empty database is not an error", state.errorMessage)
            assertFalse("an empty database is not offline", state.isOffline)
            assertFalse("an empty database is not a 401", state.isUnauthorized)
            assertEquals(0, state.streakDays)
            assertEquals(0, state.reviewsToday)
            assertNull("an empty window has no retention to report", state.retention30d)
            assertEquals("the forecast still renders 7 bars, all zero", listOf(0, 0, 0, 0, 0, 0, 0), state.forecast.map { it.due })
        }

    @Test
    fun test_offline_surfaces_the_offline_state_not_stale_numbers() =
        runTest {
            statsRepository.result = Result.Success(fullStats())
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()
            assertEquals("figures loaded", 9, viewModel.uiState.value.streakDays)

            // Stats are never cached (docs/android.md, "Offline-first sync" —
            // Room caches due cards only), so a failed refresh must not leave
            // the previous load's figures on screen.
            statsRepository.result = Result.NetworkError(IOException("unreachable"))
            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.isOffline)
            assertFalse(state.isLoading)
            assertEquals("no stale streak", 0, state.streakDays)
            assertEquals("no stale reviews_today", 0, state.reviewsToday)
            assertNull("no stale retention", state.retention30d)
            assertTrue("no stale forecast bars", state.forecast.isEmpty())
            assertTrue("no stale lapse rates", state.lapseRateByType.isEmpty())
            assertTrue("no stale guidance versions", state.lapseRateByGuidanceVersion.isEmpty())
        }

    @Test
    fun test_401_surfaces_the_auth_banner_state() =
        runTest {
            statsRepository.result = Result.Unauthorized
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("a 401 is the banner that routes to Settings, never a crash", state.isUnauthorized)
            assertFalse(state.isLoading)
            assertNull(state.errorMessage)
        }

    @Test
    fun test_ui_state_carries_next_due_at() =
        runTest {
            // Positive form of the G3 guard (issue #134): `next_due_at` is on
            // `GET /stats` now, and the UiState carries it through unchanged.
            val nextDueAt = Instant.parse("2026-09-07T04:00:00Z")
            statsRepository.result = Result.Success(fullStats(nextDueAt = nextDueAt))
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()

            assertEquals(nextDueAt, viewModel.uiState.value.nextDueAt)

            // Meta-assertions, kept from the negative form: the reflective
            // check bites on both documented shapes, so it cannot pass
            // vacuously.
            val gapField = Regex("next_?due", RegexOption.IGNORE_CASE)
            assertTrue(gapField.containsMatchIn("next_due_at"))
            assertTrue(gapField.containsMatchIn("nextDueAt"))

            val carried =
                StatsUiState::class.java.declaredFields
                    .map { it.name }
                    .filter { gapField.containsMatchIn(it) }
            assertEquals("StatsUiState carries exactly the next-due field", listOf("nextDueAt"), carried)
        }

    @Test
    fun test_refresh_re_queries_stats() =
        runTest {
            // Issue #147: refresh() on the surviving ViewModel re-queries
            // rather than serving the first load forever.
            statsRepository.result = Result.Success(fullStats(reviewsToday = 23))
            val viewModel = StatsViewModel(statsRepository, testDispatcher, FIXED_CLOCK)
            advanceUntilIdle()
            assertEquals(23, viewModel.uiState.value.reviewsToday)

            statsRepository.result = Result.Success(fullStats(reviewsToday = 25))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals("a second refresh issues a new /stats call", 2, statsRepository.calls)
            assertEquals("the updated figure is emitted", 25, viewModel.uiState.value.reviewsToday)
        }

    private class FakeStatsRepository : StatsRepository {
        var result: Result<Stats> = Result.Success(statsWithForecast())
        var calls = 0

        override suspend fun stats(): Result<Stats> {
            calls++
            return result
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 7)
        val FIXED_CLOCK: Clock = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

        fun statsWithForecast(vararg forecast: ForecastDay): Stats =
            Stats(
                streakDays = 0,
                reviewsToday = 0,
                retention30d = 0.0,
                retention30dReviews = 120,
                lapseRateByType = emptyMap(),
                lapseRateByGuidanceVersion = emptyMap(),
                curationYield = 0.0,
                nextDueAt = null,
                forecast = forecast.toList(),
            )

        fun statsWithGuidanceVersions(versions: Map<String, Double>): Stats =
            Stats(
                streakDays = 0,
                reviewsToday = 0,
                retention30d = 0.0,
                retention30dReviews = 120,
                lapseRateByType = emptyMap(),
                lapseRateByGuidanceVersion = versions,
                curationYield = 0.0,
                nextDueAt = null,
                forecast = emptyList(),
            )

        /** The documented payload (docs/api-spec.md, "Stats"). */
        fun fullStats(
            reviewsToday: Int = 23,
            nextDueAt: Instant? = null,
        ): Stats =
            Stats(
                streakDays = 9,
                reviewsToday = reviewsToday,
                retention30d = 0.87,
                retention30dReviews = 120,
                lapseRateByType = mapOf("qa" to 0.11, "cloze" to 0.18),
                lapseRateByGuidanceVersion = mapOf("1" to 0.19, "2" to 0.12),
                curationYield = 0.83,
                nextDueAt = nextDueAt,
                forecast = listOf(ForecastDay(date = TODAY.toString(), due = 14)),
            )
    }
}
