package dev.recally.ui.navigation

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NavArgument
import androidx.navigation.NavDestination
import androidx.navigation.NavType
import androidx.navigation.Navigator
import androidx.navigation.createGraph
import androidx.navigation.get
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import dev.recally.domain.model.ApproveBatchResult
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
import dev.recally.ui.screens.approve.QueueFilter
import dev.recally.ui.screens.today.TodayScreen
import dev.recally.ui.screens.today.TodayUiState
import dev.recally.ui.screens.today.TodayViewModel
import dev.recally.ui.theme.RecallyTheme
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
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
    @get:Rule
    val composeTestRule = createComposeRule()

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

    /**
     * Issue #180: one book, one destination. Today's rail click and the Decks
     * row click both run [openBookDetail] — the single navigation decision the
     * route entries own — so the same book id opens the same Book detail.
     */
    @Test
    fun test_today_book_click_navigates_to_book_detail() {
        val bookId = 11L
        val navController = TestNavHostController(context)

        @Suppress("UNCHECKED_CAST")
        val testNavigator = navController.navigatorProvider["test"] as Navigator<NavDestination>
        navController.graph =
            navController.createGraph(startDestination = Screen.Today.route) {
                addDestination(testNavigator.createDestination().apply { route = Screen.Today.route })
                addDestination(testNavigator.createDestination().apply { route = Screen.Decks.route })
                addDestination(
                    testNavigator.createDestination().apply {
                        route = Screen.BookDetail.route
                        addArgument("bookId", NavArgument.Builder().setType(NavType.LongType).build())
                    },
                )
            }

        val fromToday = navigateAndCapture(navController) { navController.openBookDetail(bookId) }

        assertEquals(Screen.BookDetail.route, fromToday.route)
        assertEquals(bookId, fromToday.bookIdArgument)

        // The route Decks produces for the same book, from the same call the
        // Decks entry makes (RecallyNavHost) — one book, one destination.
        navController.navigate(Screen.Decks.route)
        val fromDecks = navigateAndCapture(navController) { navController.openBookDetail(bookId) }

        assertEquals("Today opens the Book detail Decks opens", fromDecks, fromToday)
    }

    /** The route and bookId argument a navigation landed on, then popped. */
    private data class OpenedDestination(
        val route: String,
        val bookIdArgument: Long?,
    )

    private fun navigateAndCapture(
        navController: TestNavHostController,
        navigate: () -> Unit,
    ): OpenedDestination {
        navigate()
        val entry = navController.currentBackStackEntry
        assertNotNull("navigating opened a destination", entry)
        val opened =
            OpenedDestination(
                route = entry!!.destination.route!!,
                bookIdArgument = entry.arguments?.getLong("bookId"),
            )
        navController.popBackStack()
        return opened
    }

    /**
     * Issue #178: the two "Waiting for you" tiles are distinct counts, so they
     * must be distinct destinations. This drives the real TodayScreen through
     * the exact wiring the Today route entry applies — the tile's callback into
     * `Screen.Approve.createRoute` on a TestNavHostController — and reads back
     * the route that actually landed on the back stack.
     */
    @Test
    fun test_needs_you_tile_routes_with_the_needs_you_filter() {
        val landedRoute = routeAfterTappingTile(tileLabel = "need you")

        assertEquals(Screen.Approve.createRoute(QueueFilter.NEEDS_YOU), landedRoute)
    }

    @Test
    fun test_to_approve_tile_routes_without_a_filter() {
        // Negative: the clean-queue tile must NOT carry Needs-you. Before the
        // fix both tiles shared one bare callback, so this is the assertion
        // that fails first.
        val landedRoute = routeAfterTappingTile(tileLabel = "to approve")

        assertEquals(Screen.Approve.createRoute(QueueFilter.ALL), landedRoute)
        assertFalse(
            "the to-approve tile must not preselect the Needs-you filter",
            QueueFilter.NEEDS_YOU.name in landedRoute,
        )
    }

    /**
     * Renders the real TodayScreen with both tiles present, taps one, and
     * returns the route the Today route entry's `onOpenApprove` navigated to.
     *
     * The destinations never compose in a unit test, so Approve is served by
     * the TestNavigatorProvider's plain test navigator under its real route
     * pattern — the same technique as [test_returning_from_review_refreshes_today].
     * What is under test is the route the tile produces, so it is read back
     * from the back-stack entry rather than asserted on a captured lambda.
     */
    private fun routeAfterTappingTile(tileLabel: String): String {
        val navController = TestNavHostController(context)

        @Suppress("UNCHECKED_CAST")
        val testNavigator = navController.navigatorProvider["test"] as Navigator<NavDestination>
        navController.graph =
            navController.createGraph(startDestination = Screen.Today.route) {
                addDestination(testNavigator.createDestination().apply { route = Screen.Today.route })
                addDestination(
                    testNavigator.createDestination().apply {
                        route = Screen.Approve.route
                        addArgument(ARG_FILTER, approveFilterArgument().argument)
                    },
                )
            }

        composeTestRule.setContent {
            RecallyTheme {
                TodayScreen(
                    uiState = todayStateWithBothTiles(),
                    onStartReview = {},
                    // Verbatim the Today route entry's wiring (RecallyNavHost).
                    onOpenApprove = { filter -> navController.navigate(Screen.Approve.createRoute(filter)) },
                    onOpenSettings = {},
                    onRetry = {},
                    onBookClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(tileLabel).performClick()
        composeTestRule.waitForIdle()

        val landedEntry =
            requireNotNull(navController.currentBackStackEntry) {
                "nothing on the back stack after tapping \"$tileLabel\""
            }
        assertEquals(
            "the tile navigates to Approve",
            Screen.Approve.route,
            landedEntry.destination.route,
        )
        // The route the entry actually carries, rebuilt from its own argument.
        val filter =
            landedEntry.arguments
                ?.getString(ARG_FILTER)
                ?.let(QueueFilter::valueOf)
                ?: QueueFilter.ALL
        return Screen.Approve.createRoute(filter)
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

        override suspend fun approveBatch(cardIds: List<Long>): Result<List<ApproveBatchResult>> =
            throw UnsupportedOperationException("Today never bulk-approves")
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
        /** Both tiles present: the "need you" tile is dropped at zero. */
        fun todayStateWithBothTiles(): TodayUiState =
            TodayUiState(
                isLoading = false,
                dueCount = 3,
                newCount = 1,
                pendingReviewCount = 8,
                needsHumanCount = 3,
            )

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
