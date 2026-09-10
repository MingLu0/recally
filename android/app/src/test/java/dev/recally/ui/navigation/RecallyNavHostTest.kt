package dev.recally.ui.navigation

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NavDestination
import androidx.navigation.Navigator
import androidx.navigation.createGraph
import androidx.navigation.get
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import dev.recally.domain.model.Deck
import dev.recally.domain.model.DeckCard
import dev.recally.domain.model.DueSummary
import dev.recally.domain.model.ForecastDay
import dev.recally.domain.model.PendingQueue
import dev.recally.domain.model.Stats
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.DeckRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.StatsRepository
import dev.recally.ui.screens.today.TodayViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Resume-refresh gate for issue #147: a ViewModel survives on the back stack
 * while another route is pushed over it, so a screen that regains focus must
 * re-query on the same instance rather than render its first load forever.
 * The route entry applies [refreshOnResume] to the entry's lifecycle
 * (RecallyNavHost); this test drives that same wiring over real
 * Today → Review → back navigation and asserts the surviving TodayViewModel
 * reloads — and that pushing Review does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecallyNavHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun test_returning_from_review_refreshes_today() =
        runTest {
            val cardRepository = FakeCardRepository(Result.Success(dueSummary(dueCount = 1)))
            val viewModel =
                TodayViewModel(
                    cardRepository = cardRepository,
                    statsRepository = FakeStatsRepository(),
                    approvalRepository = FakeApprovalRepository(),
                    deckRepository = FakeDeckRepository(),
                    ioDispatcher = testDispatcher,
                    clock = Clock.fixed(Instant.parse("2026-09-09T01:00:00Z"), ZoneOffset.UTC),
                )
            advanceUntilIdle()
            val callsAfterInitialLoad = cardRepository.refreshDueCardsCalls

            val navController = TestNavHostController(context)

            // The destinations never compose in a unit test, so they are
            // served by the TestNavigatorProvider's plain test navigator
            // under the real Screen routes; a RESUMED host lifecycle owner
            // then drives the back-stack entries through the same
            // RESUMED → CREATED → RESUMED cycle the real NavHost produces.
            @Suppress("UNCHECKED_CAST")
            val testNavigator = navController.navigatorProvider["test"] as Navigator<NavDestination>
            navController.graph =
                navController.createGraph(startDestination = Screen.Today.route) {
                    addDestination(testNavigator.createDestination().apply { route = Screen.Today.route })
                    addDestination(testNavigator.createDestination().apply { route = Screen.Review.route })
                }
            val hostOwner =
                object : LifecycleOwner {
                    val registry = LifecycleRegistry(this)
                    override val lifecycle: Lifecycle get() = registry
                }
            navController.setLifecycleOwner(hostOwner)
            hostOwner.registry.currentState = Lifecycle.State.RESUMED
            advanceUntilIdle()

            // The wiring the Today route entry applies: the back-stack entry
            // survives beneath Review, and regaining RESUMED re-queries on the
            // same ViewModel instance.
            val todayEntry = navController.getBackStackEntry(Screen.Today.route)
            val observer = todayEntry.refreshOnResume(viewModel::refresh)
            try {
                navController.navigate(Screen.Review.route)
                advanceUntilIdle()
                assertEquals(
                    "pushing Review over Today does not reload it",
                    callsAfterInitialLoad,
                    cardRepository.refreshDueCardsCalls,
                )

                navController.popBackStack()
                advanceUntilIdle()
                assertSame(
                    "Today survives beneath Review — the same back-stack entry returns",
                    todayEntry,
                    navController.getBackStackEntry(Screen.Today.route),
                )
                assertEquals(
                    "returning to the surviving Today reloads it",
                    callsAfterInitialLoad + 1,
                    cardRepository.refreshDueCardsCalls,
                )
            } finally {
                todayEntry.lifecycle.removeObserver(observer)
            }
        }

    private class FakeCardRepository(
        private val refreshResult: Result<DueSummary>,
    ) : CardRepository {
        var refreshDueCardsCalls = 0

        override suspend fun dueCards(forceRefresh: Boolean): Result<DueSummary> = refreshResult

        override suspend fun refreshDueCards(): Result<DueSummary> {
            refreshDueCardsCalls++
            return refreshResult
        }

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
        private val result: Result<Stats> = Result.Success(sampleStats()),
    ) : StatsRepository {
        override suspend fun stats(): Result<Stats> = result
    }

    /** The queue is unreachable in this test; Today must render anyway. */
    private class FakeApprovalRepository : ApprovalRepository {
        override suspend fun pendingCards(): Result<PendingQueue> = Result.NetworkError(IOException("no route to host"))

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

    /** Decks are remote-only; the rail stays empty when /decks is unreachable. */
    private class FakeDeckRepository : DeckRepository {
        override suspend fun decks(): Result<List<Deck>> = Result.NetworkError(IOException("no route to host"))

        override suspend fun deckCards(
            bookId: Long,
            chapter: String?,
        ): Result<List<DeckCard>> = throw UnsupportedOperationException("Today never browses a book")
    }

    private companion object {
        fun dueSummary(dueCount: Int): DueSummary =
            DueSummary(
                dueCount = dueCount,
                newCount = 0,
                learningStepsMinutes = listOf(1, 10),
                cards = emptyList(),
            )

        fun sampleStats(): Stats =
            Stats(
                streakDays = 9,
                reviewsToday = 23,
                retention30d = 0.87,
                lapseRateByType = mapOf("qa" to 0.11),
                lapseRateByGuidanceVersion = mapOf("1" to 0.19),
                curationYield = 0.83,
                nextDueAt = null,
                forecast = listOf(ForecastDay(date = "2026-09-05", due = 14)),
            )
    }
}
