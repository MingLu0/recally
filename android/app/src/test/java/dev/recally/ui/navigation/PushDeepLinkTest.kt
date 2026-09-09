package dev.recally.ui.navigation

import android.content.Context
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import dev.recally.domain.model.DueSummary
import dev.recally.domain.model.ForecastDay
import dev.recally.domain.model.Stats
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.StatsRepository
import dev.recally.fcm.DueCardsNotification
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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

/**
 * Push deep-link gate for roadmap step 5c (issue #91): a notification tap
 * opens **Today**, never a review session (docs/android.md, "Push
 * notifications" — the count stays correct there when some cards were
 * reviewed before the tap), and Today renders without any push ever having
 * arrived (force-stop / OEM battery managers drop data messages).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushDeepLinkTest {
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
    fun test_deep_link_routes_to_today_not_review() {
        val tapIntent = DueCardsNotification.tapIntent(context)

        val route = tapIntent.deepLinkRoute()
        assertEquals("the notification deep link opens Today", Screen.Today.route, route)
        assertNotEquals("and never a review session", Screen.Review.route, route)
    }

    @Test
    fun test_deep_link_while_open_does_not_stack_a_second_today() {
        val navController = TestNavHostController(context)
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.graph =
            navController.createGraph(startDestination = Screen.Today.route) {
                composable(Screen.Today.route) { }
                composable(Screen.Review.route) { }
            }

        // A tap while Today is already on top must not stack a second one.
        navController.navigateToTodayDeepLink()
        // Nor must a tap from elsewhere — navigate away and tap twice.
        navController.navigate(Screen.Review.route)
        navController.navigateToTodayDeepLink()
        navController.navigateToTodayDeepLink()

        val todayEntries = navController.currentBackStack.value.count { it.destination.route == Screen.Today.route }
        assertEquals("exactly one Today entry in the back stack", 1, todayEntries)
        assertEquals(Screen.Today.route, navController.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun test_today_renders_having_never_received_a_push() =
        runTest {
            // No push was ever delivered: no FCM interaction anywhere in this
            // test. Today's counts come from the repositories alone, so the
            // screen renders its due count normally in the force-stop /
            // OEM-battery-manager case (docs/android.md, "Push notifications").
            val viewModel =
                TodayViewModel(
                    cardRepository = FakeCardRepository(Result.Success(dueSummary(dueCount = 12))),
                    statsRepository = FakeStatsRepository(Result.Success(sampleStats())),
                    ioDispatcher = testDispatcher,
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("the due count renders with no push ever received", 12, state.dueCount)
            assertEquals(false, state.isLoading)
        }

    private class FakeCardRepository(
        private val refreshResult: Result<DueSummary>,
    ) : CardRepository {
        override suspend fun dueCards(forceRefresh: Boolean): Result<DueSummary> = refreshResult

        override suspend fun refreshDueCards(): Result<DueSummary> = refreshResult

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
        private val result: Result<Stats> = Result.NetworkError(IOException("unused")),
    ) : StatsRepository {
        override suspend fun stats(): Result<Stats> = result
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
                forecast = listOf(ForecastDay(date = "2026-09-05", due = 14)),
            )
    }
}
