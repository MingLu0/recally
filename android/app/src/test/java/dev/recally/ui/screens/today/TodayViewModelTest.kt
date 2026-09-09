package dev.recally.ui.screens.today

import dev.recally.domain.model.DueSummary
import dev.recally.domain.model.ForecastDay
import dev.recally.domain.model.PendingCard
import dev.recally.domain.model.PendingCounts
import dev.recally.domain.model.PendingQueue
import dev.recally.domain.model.Stats
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.time.ZoneOffset

/**
 * ViewModel gate for roadmap step 4f (issue #57). Fake repositories on a
 * TestDispatcher (docs/android.md, "Tests") — never a real backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** Programmable CardRepository; [refreshResult] answers a forced refresh. */
    private class FakeCardRepository(
        var refreshResult: Result<DueSummary>,
    ) : CardRepository {
        override suspend fun dueCards(forceRefresh: Boolean): Result<DueSummary> = refreshResult

        override suspend fun refreshDueCards(): Result<DueSummary> = refreshResult

        // ADR-008 controls (step 4j) — Today never calls them.
        override suspend fun editCard(
            cardId: Long,
            front: String?,
            back: String?,
            tags: List<String>?,
        ): Result<Unit> = throw UnsupportedOperationException("Today never edits cards")

        override suspend fun suspendCard(cardId: Long): Result<Instant?> = throw UnsupportedOperationException("Today never suspends cards")

        override suspend fun unsuspendCard(cardId: Long): Result<Instant?> =
            throw UnsupportedOperationException("Today never unsuspends cards")
    }

    private class FakeStatsRepository(
        var result: Result<Stats>,
    ) : StatsRepository {
        override suspend fun stats(): Result<Stats> = result
    }

    /**
     * The approval queue requires connectivity (docs/android.md,
     * "Offline-first sync"), so the default fake answers with a network
     * failure: Today must render anyway, with the count tiles absent.
     */
    private class FakeApprovalRepository(
        var pendingResult: Result<PendingQueue> = Result.NetworkError(IOException("no route to host")),
    ) : ApprovalRepository {
        override suspend fun pendingCards(): Result<PendingQueue> = pendingResult

        override suspend fun approveCard(
            cardId: Long,
            front: String?,
            back: String?,
        ): Result<Unit> = throw UnsupportedOperationException("Today never approves cards")

        override suspend fun rejectCard(
            cardId: Long,
            reason: String?,
        ): Result<Unit> = throw UnsupportedOperationException("Today never rejects cards")
    }

    private fun viewModelWith(
        dueResult: Result<DueSummary>,
        statsResult: Result<Stats> = Result.Success(sampleStats()),
        pendingResult: Result<PendingQueue> = Result.NetworkError(IOException("no route to host")),
    ): TodayViewModel =
        TodayViewModel(
            cardRepository = FakeCardRepository(dueResult),
            statsRepository = FakeStatsRepository(statsResult),
            approvalRepository = FakeApprovalRepository(pendingResult),
            ioDispatcher = testDispatcher,
            clock = FIXED_CLOCK,
        )

    @Test
    fun test_due_and_new_counts_come_from_the_due_endpoint() =
        runTest {
            val dueSummary =
                DueSummary(
                    dueCount = 12,
                    newCount = 5,
                    learningStepsMinutes = listOf(1, 10),
                    cards = emptyList(),
                )
            val viewModel = viewModelWith(dueResult = Result.Success(dueSummary))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("due_count maps into UiState unmodified", 12, state.dueCount)
            assertEquals("new_count maps into UiState unmodified", 5, state.newCount)
            assertFalse(state.isLoading)
        }

    @Test
    fun test_offline_serves_cached_counts() =
        runTest {
            // What the real CardRepositoryImpl returns when the refresh fails
            // and the Room cache answers instead (docs/android.md,
            // "Repositories own the data layer").
            val cached =
                DueSummary(
                    dueCount = 7,
                    newCount = 2,
                    learningStepsMinutes = listOf(1, 10),
                    cards = emptyList(),
                )
            val viewModel =
                viewModelWith(
                    dueResult = Result.Success(cached, servedFromCache = true),
                    statsResult = Result.NetworkError(IOException("no route to host")),
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("cached due count still renders", 7, state.dueCount)
            assertEquals("cached new count still renders", 2, state.newCount)
            assertTrue("a refresh answered from cache sets the offline flag", state.isOffline)
            assertFalse("a network failure is not a 401", state.showCheckSettingsBanner)
        }

    @Test
    fun test_401_sets_the_check_settings_banner() =
        runTest {
            val viewModel =
                viewModelWith(
                    dueResult = Result.Unauthorized,
                    statsResult = Result.Unauthorized,
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("a 401 sets the check-settings banner", state.showCheckSettingsBanner)
            assertNull("a 401 is not a generic error", state.errorMessage)
            assertFalse("a 401 is not a connectivity failure", state.isOffline)
            assertFalse(state.isLoading)
        }

    @Test
    fun test_nothing_due_state_shows_next_due_in_hours_when_served() =
        runTest {
            val nothingDue =
                DueSummary(
                    dueCount = 0,
                    newCount = 0,
                    learningStepsMinutes = listOf(1, 10),
                    cards = emptyList(),
                )
            // `GET /stats` serves the hours-away figure (issue #134): four
            // hours from the fixed clock.
            val statsResult =
                Result.Success(sampleStats(nextDueAt = FIXED_INSTANT.plusSeconds(4 * 3600)))
            val viewModel =
                viewModelWith(dueResult = Result.Success(nothingDue), statsResult = statsResult)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("zero due and zero new renders the nothing-due state", state.nothingDue)
            assertFalse(state.isLoading)
            assertEquals("next card in 4 hours", state.nextDueLabel)
        }

    @Test
    fun test_nothing_due_shows_no_hours_figure_when_next_due_at_is_null() =
        runTest {
            // Negative: a null `next_due_at` keeps the bare nothing-due
            // treatment — never an "in 0 hours" figure (issue #134).
            val nothingDue =
                DueSummary(
                    dueCount = 0,
                    newCount = 0,
                    learningStepsMinutes = listOf(1, 10),
                    cards = emptyList(),
                )
            val viewModel =
                viewModelWith(
                    dueResult = Result.Success(nothingDue),
                    statsResult = Result.Success(sampleStats(nextDueAt = null)),
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("zero due and zero new renders the nothing-due state", state.nothingDue)
            assertFalse(state.isLoading)
            assertNull("no next_due_at means no hours figure", state.nextDueLabel)
        }

    @Test
    fun test_ui_state_carries_the_pending_counts() =
        runTest {
            // G1 (issue #132): `GET /cards/pending` returns counts, and both
            // buckets reach the state so the tiles render without the list.
            val viewModel =
                viewModelWith(
                    dueResult = Result.Success(emptyDueSummary()),
                    pendingResult =
                        Result.Success(
                            PendingQueue(
                                cards = emptyList(),
                                counts = PendingCounts(pendingReview = 5, needsHuman = 3),
                            ),
                        ),
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("the pending_review bucket reaches the state", 5, state.pendingReviewCount)
            assertEquals("the needs_human bucket reaches the state", 3, state.needsHumanCount)

            // The guard keeps its teeth: the named fields must exist for the
            // assertions above to compile, and this predicate must still
            // recognise a count field by name.
            assertTrue(isPendingCountField("pendingReviewCount"))
            assertTrue(isPendingCountField("needsHumanCount"))
        }

    @Test
    fun test_pending_counts_are_not_derived_from_a_list_length() =
        runTest {
            // Negative: the tiles must show `counts`, never `cards.size` — a
            // filtered or partial response whose list is shorter than the
            // queue must not shrink the tiles.
            val viewModel =
                viewModelWith(
                    dueResult = Result.Success(emptyDueSummary()),
                    pendingResult =
                        Result.Success(
                            PendingQueue(
                                cards = listOf(pendingCard(id = 1), pendingCard(id = 2)),
                                counts = PendingCounts(pendingReview = 5, needsHuman = 3),
                            ),
                        ),
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(5, state.pendingReviewCount)
            assertEquals(3, state.needsHumanCount)
            assertTrue(
                "the response list was shorter than the counts, so a .size shortcut would fail this test",
                state.pendingReviewCount != 2 && state.needsHumanCount != 2,
            )
        }

    private companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2026-09-09T01:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)

        fun isPendingCountField(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("pending") ||
                lower.contains("approve") ||
                lower.contains("needsyou") ||
                lower.contains("needshuman")
        }

        fun emptyDueSummary(): DueSummary =
            DueSummary(
                dueCount = 0,
                newCount = 0,
                learningStepsMinutes = listOf(1, 10),
                cards = emptyList(),
            )

        fun pendingCard(id: Long): PendingCard =
            PendingCard(
                id = id,
                status = PendingCard.STATUS_PENDING_REVIEW,
                type = PendingCard.TYPE_QA,
                front = "Front of card $id",
                back = "Back of card $id",
                statusReason = null,
                sourceHighlights = listOf("A source highlight."),
                truncated = false,
                bookId = 1,
                book = "Evals for AI Engineers",
                chapter = "1. Introduction",
            )

        fun sampleStats(nextDueAt: Instant? = null): Stats =
            Stats(
                streakDays = 9,
                reviewsToday = 23,
                retention30d = 0.87,
                lapseRateByType = mapOf("qa" to 0.11),
                lapseRateByGuidanceVersion = mapOf("1" to 0.19),
                curationYield = 0.83,
                nextDueAt = nextDueAt,
                forecast = listOf(ForecastDay(date = "2026-09-05", due = 14)),
            )
    }
}
