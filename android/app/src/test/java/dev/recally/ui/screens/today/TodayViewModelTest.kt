package dev.recally.ui.screens.today

import dev.recally.domain.model.ApproveBatchResult
import dev.recally.domain.model.Deck
import dev.recally.domain.model.DeckCard
import dev.recally.domain.model.DueSummary
import dev.recally.domain.model.ForecastDay
import dev.recally.domain.model.PendingCard
import dev.recally.domain.model.PendingCounts
import dev.recally.domain.model.PendingQueue
import dev.recally.domain.model.Stats
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.DeckRepository
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
        var refreshDueCardsCalls = 0

        override suspend fun dueCards(forceRefresh: Boolean): Result<DueSummary> = refreshResult

        override suspend fun refreshDueCards(): Result<DueSummary> {
            refreshDueCardsCalls++
            return refreshResult
        }

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
        var statsCalls = 0

        override suspend fun stats(): Result<Stats> {
            statsCalls++
            return result
        }
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

        override suspend fun approveBatch(cardIds: List<Long>): Result<List<ApproveBatchResult>> =
            throw UnsupportedOperationException("Today never bulk-approves")
    }

    /**
     * The book rail rides `GET /decks` (G2, issue #133). Decks are
     * remote-only for v1 (docs/android.md, "Offline-first sync"), so the
     * default fake answers with a network failure: the rail stays empty and
     * the rest of Today renders anyway.
     */
    private class FakeDeckRepository(
        var decksResult: Result<List<Deck>> = Result.NetworkError(IOException("no route to host")),
    ) : DeckRepository {
        override suspend fun decks(): Result<List<Deck>> = decksResult

        override suspend fun deckCards(
            bookId: Long,
            chapter: String?,
        ): Result<List<DeckCard>> = throw UnsupportedOperationException("Today never browses a book")
    }

    private fun viewModelWith(
        dueResult: Result<DueSummary>,
        statsResult: Result<Stats> = Result.Success(sampleStats()),
        pendingResult: Result<PendingQueue> = Result.NetworkError(IOException("no route to host")),
        decksResult: Result<List<Deck>> = Result.NetworkError(IOException("no route to host")),
    ): TodayViewModel =
        TodayViewModel(
            cardRepository = FakeCardRepository(dueResult),
            statsRepository = FakeStatsRepository(statsResult),
            approvalRepository = FakeApprovalRepository(pendingResult),
            deckRepository = FakeDeckRepository(decksResult),
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

    @Test
    fun test_refresh_re_queries_due_counts_and_stats() =
        runTest {
            // Issue #147: refresh() on the surviving ViewModel re-hits the
            // repositories rather than serving the first load forever.
            val cardRepository = FakeCardRepository(Result.Success(dueSummary(dueCount = 1)))
            val statsRepository = FakeStatsRepository(Result.Success(sampleStats(reviewsToday = 24)))
            val viewModel =
                TodayViewModel(
                    cardRepository = cardRepository,
                    statsRepository = statsRepository,
                    approvalRepository = FakeApprovalRepository(),
                    deckRepository = FakeDeckRepository(),
                    ioDispatcher = testDispatcher,
                    clock = FIXED_CLOCK,
                )
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.dueCount)
            assertEquals(24, viewModel.uiState.value.reviewsToday)

            // The review session rated the card; the server state moved on.
            cardRepository.refreshResult = Result.Success(emptyDueSummary())
            statsRepository.result = Result.Success(sampleStats(reviewsToday = 25))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals("a second refresh issues a new /reviews/due call", 2, cardRepository.refreshDueCardsCalls)
            assertEquals("a second refresh issues a new /stats call", 2, statsRepository.statsCalls)
            val state = viewModel.uiState.value
            assertEquals("the updated due count is emitted", 0, state.dueCount)
            assertEquals("the reviewed count increments", 25, state.reviewsToday)
        }

    @Test
    fun test_nothing_due_state_is_emitted_when_the_last_due_card_is_rated() =
        runTest {
            // Issue #147: rating the last due card, then refreshing, reaches
            // the nothing-due treatment (design-system.md, "States").
            val cardRepository = FakeCardRepository(Result.Success(dueSummary(dueCount = 1)))
            val statsRepository = FakeStatsRepository(Result.Success(sampleStats(reviewsToday = 24)))
            val viewModel =
                TodayViewModel(
                    cardRepository = cardRepository,
                    statsRepository = statsRepository,
                    approvalRepository = FakeApprovalRepository(),
                    deckRepository = FakeDeckRepository(),
                    ioDispatcher = testDispatcher,
                    clock = FIXED_CLOCK,
                )
            advanceUntilIdle()
            assertFalse("one due card is not the nothing-due state", viewModel.uiState.value.nothingDue)

            cardRepository.refreshResult = Result.Success(emptyDueSummary())
            statsRepository.result =
                Result.Success(sampleStats(reviewsToday = 25, nextDueAt = FIXED_INSTANT.plusSeconds(23 * 3600)))
            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("due_count 0 after the rating emits the nothing-due state", state.nothingDue)
            assertEquals("next card in 23 hours", state.nextDueLabel)
        }

    @Test
    fun test_offline_is_reflected_on_refresh_without_recreating_the_view_model() =
        runTest {
            // Issue #147: a connectivity drop between loads surfaces on the
            // next refresh — the same ViewModel instance, never a cold start.
            val cardRepository = FakeCardRepository(Result.Success(dueSummary(dueCount = 3)))
            val viewModel =
                TodayViewModel(
                    cardRepository = cardRepository,
                    statsRepository = FakeStatsRepository(Result.Success(sampleStats())),
                    approvalRepository = FakeApprovalRepository(),
                    deckRepository = FakeDeckRepository(),
                    ioDispatcher = testDispatcher,
                    clock = FIXED_CLOCK,
                )
            advanceUntilIdle()
            assertFalse("the first load is online", viewModel.uiState.value.isOffline)

            cardRepository.refreshResult = Result.NetworkError(IOException("no route to host"))
            viewModel.refresh()
            advanceUntilIdle()

            assertTrue("a refresh with no connectivity sets the offline bar", viewModel.uiState.value.isOffline)
        }

    @Test
    fun test_book_rail_progress_comes_from_the_decks_endpoint() =
        runTest {
            // G2 (issue #133): `GET /decks` serves `progress`, and it reaches
            // the state unmodified. total = 10 with due = 5 would give 0.5
            // from any client-side (total - due) / total arithmetic; the
            // server says 0.42, so a recomputation fails this test.
            val viewModel =
                viewModelWith(
                    dueResult = Result.Success(emptyDueSummary()),
                    decksResult =
                        Result.Success(
                            listOf(
                                Deck(
                                    bookId = 2,
                                    title = "Building Generative AI Services with FastAPI",
                                    total = 10,
                                    due = 5,
                                    progress = 0.42f,
                                    chapters = 3,
                                    truncated = 0,
                                ),
                            ),
                        ),
                )
            advanceUntilIdle()

            val book =
                viewModel.uiState.value.books
                    .single()
            assertEquals("the book's title reaches the rail", "Building Generative AI Services with FastAPI", book.title)
            assertEquals(
                "progress maps into UiState unmodified — never recomputed from total/due",
                0.42f,
                book.progress,
            )
            assertEquals(10, book.total)
            assertEquals(5, book.due)
        }

    @Test
    fun test_book_rail_is_empty_when_decks_is_unreachable() =
        runTest {
            // Decks are remote-only, so a failing `GET /decks` leaves the
            // rail empty — and the failure is silent: the stats strip and due
            // counts still render (docs/android.md, screen → endpoint map).
            val viewModel =
                viewModelWith(
                    dueResult = Result.Success(dueSummary(dueCount = 3)),
                    decksResult = Result.NetworkError(IOException("no route to host")),
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("a failing /decks leaves the rail empty", state.books.isEmpty())
            assertEquals("the stats strip is not blanked", 9, state.streakDays)
            assertEquals("the due count is not blanked", 3, state.dueCount)
            assertNull("an unreachable rail is not an error banner", state.errorMessage)
        }

    @Test
    fun test_books_are_populated_when_decks_load() =
        runTest {
            // `GET /decks` serves an ordered list and the rail renders it in
            // served order — the ViewModel neither sorts nor filters it.
            val servedDecks =
                listOf(
                    Deck(
                        bookId = 1,
                        title = "Evals for AI Engineers",
                        total = 48,
                        due = 6,
                        progress = 0.62f,
                        chapters = 9,
                        truncated = 0,
                    ),
                    Deck(
                        bookId = 7,
                        title = "30 Agents in 30 Days",
                        total = 83,
                        due = 0,
                        progress = 0.24f,
                        chapters = 4,
                        truncated = 2,
                    ),
                )
            val viewModel =
                viewModelWith(
                    dueResult = Result.Success(emptyDueSummary()),
                    decksResult = Result.Success(servedDecks),
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("both served decks reach the rail", 2, state.books.size)
            assertEquals(
                "the rail keeps the served order",
                listOf(1L, 7L),
                state.books.map { it.bookId },
            )
            assertEquals("Evals for AI Engineers", state.books[0].title)
            assertEquals("30 Agents in 30 Days", state.books[1].title)
            assertFalse(
                "a successful load clears the not-loaded flag",
                state.booksFailedToLoad,
            )
        }

    @Test
    fun test_deck_failure_is_distinguishable_from_an_empty_library() =
        runTest {
            // The whole point of issue #189: a rail that failed to load must
            // not read as a user with no books. Both leave `books` empty, so
            // the flag is the only thing that tells them apart.
            val failedViewModel =
                viewModelWith(
                    dueResult = Result.Success(emptyDueSummary()),
                    decksResult = Result.NetworkError(IOException("no route to host")),
                )
            advanceUntilIdle()

            val failedState = failedViewModel.uiState.value
            assertTrue("a failing /decks leaves the rail empty", failedState.books.isEmpty())
            assertTrue(
                "a failing /decks sets the not-loaded flag",
                failedState.booksFailedToLoad,
            )

            val emptyViewModel =
                viewModelWith(
                    dueResult = Result.Success(emptyDueSummary()),
                    decksResult = Result.Success(emptyList()),
                )
            advanceUntilIdle()

            val emptyState = emptyViewModel.uiState.value
            assertTrue("a genuinely empty library is empty", emptyState.books.isEmpty())
            assertFalse(
                "a loaded-but-empty library never sets the not-loaded flag",
                emptyState.booksFailedToLoad,
            )
        }

    @Test
    fun test_deck_failure_does_not_set_the_screen_error_message() =
        runTest {
            // The silent-failure rule stays (docs/android.md, "Offline-first
            // sync"): the rail never blanks Today, and a decks failure is not
            // a screen-level error.
            val viewModel =
                viewModelWith(
                    dueResult = Result.Success(dueSummary(dueCount = 3)),
                    decksResult = Result.NetworkError(IOException("no route to host")),
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull("a decks failure is not a screen error", state.errorMessage)
            assertEquals("the due count is not blanked", 3, state.dueCount)
            assertEquals("the stats strip is not blanked", 9, state.streakDays)
        }

    @Test
    fun test_book_rail_renders_a_zero_progress_book() =
        runTest {
            // A book with `progress: 0.0` (approved cards, none in review
            // state yet) is a real row, not an absent one.
            val viewModel =
                viewModelWith(
                    dueResult = Result.Success(emptyDueSummary()),
                    decksResult =
                        Result.Success(
                            listOf(
                                Deck(
                                    bookId = 1,
                                    title = "Evals for AI Engineers",
                                    total = 48,
                                    due = 6,
                                    progress = 0.62f,
                                    chapters = 3,
                                    truncated = 0,
                                ),
                                Deck(
                                    bookId = 7,
                                    title = "30 Agents in 30 Days",
                                    total = 83,
                                    due = 0,
                                    progress = 0.0f,
                                    chapters = 3,
                                    truncated = 0,
                                ),
                            ),
                        ),
                )
            advanceUntilIdle()

            val books = viewModel.uiState.value.books
            assertEquals("both books render, zero progress included", 2, books.size)
            val zeroProgressBook = books.first { it.bookId == 7L }
            assertEquals(0.0f, zeroProgressBook.progress)
        }

    @Test
    fun test_today_rail_derives_no_truncated_count_of_its_own() {
        // G6 landed as a `GET /decks` field in issue #173, so `Deck` now carries
        // `truncated` — the Decks row badges it. What this guard still protects is
        // the Today rail: it renders no truncated count, and TodayUiState derives
        // none. The G2 book field must be present and server-sourced (issue #154).
        val truncatedField = Regex("truncated", RegexOption.IGNORE_CASE)
        // Meta-assertion: the check bites.
        assertTrue(truncatedField.containsMatchIn("truncatedCount"))

        val railField =
            TodayUiState::class.java.declaredFields.firstOrNull { it.name == "books" }
        assertTrue("the G2 book rail field must exist on TodayUiState", railField != null)
        assertTrue(
            "the rail is a list of books served by GET /decks",
            railField!!.type == List::class.java,
        )

        // The server's count reaches the rail's Deck objects untouched...
        assertTrue(
            "Deck must carry the G6 truncated count from GET /decks",
            Deck::class.java.declaredFields.any { truncatedField.containsMatchIn(it.name) },
        )
        // ...and TodayUiState adds no aggregate or per-rail count of its own.
        val offending =
            TodayUiState::class.java.declaredFields
                .map { it.name }
                .filter { truncatedField.containsMatchIn(it) }
        assertTrue("TodayUiState derives a truncated count: $offending", offending.isEmpty())
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

        fun dueSummary(dueCount: Int): DueSummary =
            DueSummary(
                dueCount = dueCount,
                newCount = 0,
                learningStepsMinutes = listOf(1, 10),
                cards = emptyList(),
            )

        fun sampleStats(
            reviewsToday: Int = 23,
            nextDueAt: Instant? = null,
        ): Stats =
            Stats(
                streakDays = 9,
                reviewsToday = reviewsToday,
                retention30d = 0.87,
                retention30dReviews = 120,
                lapseRateByType = mapOf("qa" to 0.11),
                lapseRateByGuidanceVersion = mapOf("1" to 0.19),
                curationYield = 0.83,
                nextDueAt = nextDueAt,
                forecast = listOf(ForecastDay(date = "2026-09-05", due = 14)),
            )
    }
}
