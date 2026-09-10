package dev.recally.ui.screens.review

import dev.recally.data.sync.RatingOutbox
import dev.recally.domain.model.Card
import dev.recally.domain.model.DueSummary
import dev.recally.domain.model.RateOutcome
import dev.recally.domain.model.ReviewRating
import dev.recally.domain.model.Stats
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.ReviewRepository
import dev.recally.domain.repository.SettingsRepository
import dev.recally.domain.repository.StatsRepository
import dev.recally.domain.repository.StoredConnection
import dev.recally.ui.components.buildClozeAnnotatedString
import dev.recally.ui.components.parseClozeSegments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ViewModel tests against fake repositories on a test dispatcher
 * (docs/android.md, "Tests"). The named tests in this class are the step 4g
 * gate (ADR-012); they pin the hard-rule surface of the review session:
 * flip-to-rate timing, no answer content before the flip, interval hints on
 * Again/Hard only, the step-seeded re-queue, and cards-left progress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val sessionStart: Instant = Instant.parse("2026-09-08T08:00:00Z")

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun test_response_ms_measured_from_flip_not_from_display() =
        runTest {
            val clock = MutableClock(sessionStart)
            val reviewRepository = FakeReviewRepository()
            val viewModel = newViewModel(cards = listOf(card(id = 1, step = null)), clock = clock, reviewRepository = reviewRepository)

            // The card sits on its front for a full minute — none of this may count.
            clock.advance(Duration.ofSeconds(60))
            viewModel.flip()
            clock.advance(Duration.ofSeconds(5))
            viewModel.rate(RATING_GOOD)

            assertEquals(1, reviewRepository.ratings.size)
            assertEquals(
                "response_ms must be flip-to-rate (5 s), not display-to-rate (65 s)",
                5_000L,
                reviewRepository.ratings.single().responseMs,
            )
        }

    @Test
    fun test_no_answer_content_in_ui_state_before_flip() =
        runTest {
            val viewModel = newViewModel(cards = listOf(card(id = 1, step = null)))

            val state = viewModel.uiState.value
            assertNotNull(state.card)
            assertEquals("Q1", state.card?.front)
            assertNull("the back text must not reach the UiState before the flip", state.answer)
            assertNull("no rating affordance before the flip", state.ratingHints)
            assertFalse(state.isFlipped)
        }

    @Test
    fun test_interval_hints_only_on_again_and_hard() =
        runTest {
            val viewModel = newViewModel(cards = listOf(card(id = 1, step = 0)))
            viewModel.flip()

            val hints = viewModel.uiState.value.ratingHints
            assertNotNull(hints)
            assertEquals("<1m", hints?.again)
            assertEquals("10m", hints?.hard)
            assertNull("a Good projection is scheduling state — hard rule 5", hints?.good)
            assertNull("an Easy projection is scheduling state — hard rule 5", hints?.easy)
        }

    @Test
    fun test_requeue_uses_the_cards_current_step() =
        runTest {
            val clock = MutableClock(sessionStart)
            val reviewRepository = FakeReviewRepository().apply { offline() }
            // Card 1 arrives at step 1: an Again must wait the step-1 interval (10m), not step 0 (1m).
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = 1), card(id = 2, step = null), card(id = 3, step = null)),
                    clock = clock,
                    reviewRepository = reviewRepository,
                )

            viewModel.flip()
            viewModel.rate(RATING_AGAIN)
            assertEquals(
                2L,
                viewModel.uiState.value.card
                    ?.id,
            )

            viewModel.flip()
            clock.advance(Duration.ofMinutes(2))
            viewModel.rate(RATING_GOOD)
            assertEquals(
                "card 1 waits the step-1 interval (10m), so card 3 comes next",
                3L,
                viewModel.uiState.value.card
                    ?.id,
            )

            viewModel.flip()
            clock.advance(Duration.ofMinutes(9))
            viewModel.rate(RATING_GOOD)
            assertEquals(
                "after the step-1 interval elapses, card 1 returns",
                1L,
                viewModel.uiState.value.card
                    ?.id,
            )
        }

    @Test
    fun test_offline_step_counter_advances_without_a_rate_response() =
        runTest {
            val clock = MutableClock(sessionStart)
            val reviewRepository = FakeReviewRepository().apply { offline() }
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = 0), card(id = 2, step = null), card(id = 3, step = null), card(id = 4, step = null)),
                    clock = clock,
                    reviewRepository = reviewRepository,
                )

            // First Again: step 0, so card 1 returns after 1m.
            viewModel.flip()
            viewModel.rate(RATING_AGAIN)
            clock.advance(Duration.ofMinutes(2))
            viewModel.flip()
            viewModel.rate(RATING_GOOD) // card 2
            assertEquals(
                "card 1's step-0 interval (1m) has elapsed, so it returns",
                1L,
                viewModel.uiState.value.card
                    ?.id,
            )

            // Second Again, still no rate response: the local step must advance
            // to 1, so the card now waits 10m — not 1m.
            viewModel.flip()
            viewModel.rate(RATING_AGAIN)
            assertEquals(
                3L,
                viewModel.uiState.value.card
                    ?.id,
            )
            clock.advance(Duration.ofMinutes(3))
            viewModel.flip()
            viewModel.rate(RATING_GOOD) // card 3
            assertEquals(
                "the local step advanced, so card 1 is not due yet and card 4 comes next",
                4L,
                viewModel.uiState.value.card
                    ?.id,
            )
        }

    @Test
    fun test_offline_stops_requeueing_past_the_last_step() =
        runTest {
            val clock = MutableClock(sessionStart)
            val reviewRepository = FakeReviewRepository().apply { offline() }
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = 0)),
                    clock = clock,
                    reviewRepository = reviewRepository,
                )

            // Steps [1, 10]: rated Again at step 0 and at step 1 the card comes
            // back; past the last step it must not be re-queued again.
            viewModel.flip()
            viewModel.rate(RATING_AGAIN)
            viewModel.flip()
            viewModel.rate(RATING_AGAIN)
            viewModel.flip()
            viewModel.rate(RATING_AGAIN)

            val state = viewModel.uiState.value
            assertNull("past the last learning step the card is not re-queued", state.card)
            assertNotNull("the session ends instead of looping the card forever", state.summary)
            assertEquals(3, state.summary?.reviewedCount)
        }

    @Test
    fun test_rate_response_step_overwrites_the_local_counter() =
        runTest {
            val clock = MutableClock(sessionStart)
            val reviewRepository = FakeReviewRepository()
            reviewRepository.offline()
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = 0), card(id = 2, step = null), card(id = 3, step = null), card(id = 4, step = null)),
                    clock = clock,
                    reviewRepository = reviewRepository,
                )

            // Offline Again: local step advances 0 → 1 (would re-queue at 10m next).
            viewModel.flip()
            viewModel.rate(RATING_AGAIN)
            clock.advance(Duration.ofMinutes(2))

            // Back online; the server scored card 1's Again as step 0.
            reviewRepository.online { rating -> outcome(rating, step = 0) }
            viewModel.flip()
            viewModel.rate(RATING_GOOD) // card 2
            assertEquals(
                1L,
                viewModel.uiState.value.card
                    ?.id,
            )
            viewModel.flip()
            viewModel.rate(RATING_AGAIN) // card 1 again, response step = 0
            assertEquals(
                3L,
                viewModel.uiState.value.card
                    ?.id,
            )

            clock.advance(Duration.ofMinutes(2))
            viewModel.flip()
            viewModel.rate(RATING_GOOD) // card 3
            assertEquals(
                "the response step (0) replaced the local counter (1): card 1 waited 1m, not 10m",
                1L,
                viewModel.uiState.value.card
                    ?.id,
            )
        }

    @Test
    fun test_cards_left_includes_the_card_on_screen() =
        runTest {
            val viewModel = newViewModel(cards = listOf(card(id = 1, step = null)))

            val state = viewModel.uiState.value
            assertNotNull(state.card)
            assertEquals(
                "the card in front of you has not been done yet — it is remaining work (#148)",
                1,
                state.cardsLeft,
            )
        }

    @Test
    fun test_cards_left_reaches_zero_only_at_session_end() =
        runTest {
            val viewModel = newViewModel(cards = listOf(card(id = 1, step = null)))

            assertTrue(
                "the count must not read zero while a card waits to be rated",
                viewModel.uiState.value.cardsLeft > 0,
            )

            viewModel.flip()
            viewModel.rate(RATING_GOOD)

            val state = viewModel.uiState.value
            assertNotNull("the session is finished", state.summary)
            assertEquals("zero arrives with the summary sheet, not before", 0, state.cardsLeft)
        }

    @Test
    fun test_a_card_queued_for_repeat_is_counted_once() =
        runTest {
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = 0), card(id = 2, step = null)),
                )

            viewModel.flip()
            viewModel.rate(RATING_AGAIN) // card 1 re-queued; card 2 now on screen

            val state = viewModel.uiState.value
            assertEquals(2L, state.card?.id)
            assertEquals(1, state.toRepeatCount)
            assertEquals(
                "doneCount + cardsLeft is the true outstanding count: card 2 on screen + card 1's repeat",
                2,
                state.doneCount + state.cardsLeft,
            )
        }

    @Test
    fun test_progress_is_cards_left_not_a_fixed_total() =
        runTest {
            val clock = MutableClock(sessionStart)
            val reviewRepository = FakeReviewRepository().apply { offline() }
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = 0), card(id = 2, step = null), card(id = 3, step = null)),
                    clock = clock,
                    reviewRepository = reviewRepository,
                )

            assertEquals(2, viewModel.uiState.value.cardsLeft)
            viewModel.flip()
            viewModel.rate(RATING_AGAIN)
            assertEquals(
                "the re-queued repeat joins the remaining count — a fixed total could only shrink",
                2,
                viewModel.uiState.value.cardsLeft,
            )

            // Drain the session: the repeat means 3 cards produce 4 presentations.
            clock.advance(Duration.ofMinutes(30))
            viewModel.flip()
            viewModel.rate(RATING_GOOD) // card 2
            viewModel.flip()
            viewModel.rate(RATING_GOOD) // card 3
            viewModel.flip()
            viewModel.rate(RATING_GOOD) // card 1's repeat
            val summary = viewModel.uiState.value.summary
            assertNotNull(summary)
            assertEquals(4, summary?.reviewedCount)

            val fields = ReviewUiState::class.java.declaredFields.map { it.name.lowercase() }
            assertTrue(
                "UiState must carry no fixed denominator (found: $fields)",
                fields.none { "total" in it || "denominator" in it },
            )
        }

    @Test
    fun test_bury_unavailable_offline() =
        runTest {
            val reviewRepository = FakeReviewRepository().apply { offline() }
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = null), card(id = 2, step = null)),
                    reviewRepository = reviewRepository,
                )

            viewModel.flip()
            viewModel.rate(RATING_GOOD) // no response — the session now knows it is offline
            assertTrue(viewModel.uiState.value.isOffline)
            assertFalse("bury requires connectivity and is never queued", viewModel.uiState.value.buryAvailable)

            viewModel.bury()
            assertTrue("a disabled bury must not reach the repository", reviewRepository.buryCalls.isEmpty())
        }

    @Test
    fun test_bury_only_before_flip_and_edit_only_after() =
        runTest {
            val viewModel = newViewModel(cards = listOf(card(id = 1, step = null)))

            assertTrue("bury is the pre-flip escape hatch", viewModel.uiState.value.buryAvailable)
            assertFalse("editing the answer requires seeing it", viewModel.uiState.value.editAvailable)

            viewModel.flip()
            assertFalse("burying after the flip would be a rating dodge", viewModel.uiState.value.buryAvailable)
            assertTrue(viewModel.uiState.value.editAvailable)
        }

    @Test
    fun test_lapse_count_ignores_duplicates() =
        runTest {
            val clock = MutableClock(sessionStart)
            val reviewRepository = FakeReviewRepository()
            val responses =
                mutableListOf<(ReviewRating) -> Result<RateOutcome>>(
                    { rating -> outcome(rating, step = 0, lapsed = true) },
                    // A replayed rating reports duplicate: true and lapsed: false.
                    { rating -> outcome(rating, step = 1, lapsed = false, duplicate = true) },
                    { rating -> outcome(rating, step = null) },
                )
            // removeAt(0), not removeFirst(): the latter resolves to JDK 21's
            // SequencedCollection method, which android.jar does not have.
            reviewRepository.rateHandler = { rating -> responses.removeAt(0)(rating) }
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = 0)),
                    clock = clock,
                    reviewRepository = reviewRepository,
                )

            viewModel.flip()
            viewModel.rate(RATING_AGAIN) // lapsed, not a duplicate
            viewModel.flip()
            viewModel.rate(RATING_AGAIN) // duplicate — must not move the lapse count
            viewModel.flip()
            viewModel.rate(RATING_GOOD)

            val summary = viewModel.uiState.value.summary
            assertNotNull(summary)
            assertEquals(3, summary?.reviewedCount)
            assertEquals(2, summary?.againCount)
            assertEquals(1, summary?.goodOrEasyCount)
            assertEquals("only the non-duplicate lapse counts", 1, summary?.lapseCount)
        }

    @Test
    fun test_cloze_never_renders_raw_braces() {
        val front = "The {{c1::powerhouse}} of the cell is the {{c1::mitochondria}}."

        // Both phases render from the same parsed spine; `revealed` only
        // restyles it, so asserting the spine covers unflipped and flipped.
        val rendered = buildClozeAnnotatedString(front).text
        assertFalse("rendering shows raw braces: $rendered", rendered.contains("{{c1::"))
        assertFalse("rendering shows raw braces: $rendered", rendered.contains("}}"))

        // The answers survive as inline-content alternate text — the blank
        // keeps their width unflipped and their glyphs flipped.
        val answers = parseClozeSegments(front).filter { it.isAnswer }
        assertEquals(listOf("powerhouse", "mitochondria"), answers.map { it.text })
        assertTrue(rendered.contains("powerhouse"))
        assertTrue(rendered.contains("mitochondria"))
    }

    @Test
    fun test_a_failed_rating_is_written_to_the_outbox() =
        runTest {
            val clock = MutableClock(sessionStart)
            val reviewRepository = FakeReviewRepository().apply { offline() }
            val ratingOutbox = FakeRatingOutbox()
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = null)),
                    clock = clock,
                    reviewRepository = reviewRepository,
                    ratingOutbox = ratingOutbox,
                )

            viewModel.flip()
            clock.advance(Duration.ofSeconds(5))
            viewModel.rate(RATING_GOOD)

            val recorded = ratingOutbox.recorded
            assertEquals(
                "a failed post must land in the Room outbox, not only in memory (#115)",
                1,
                recorded.size,
            )
            assertEquals(1L, recorded.single().cardId)
            assertEquals(RATING_GOOD, recorded.single().rating)
            assertEquals(
                "the outbox row carries the same flip-to-rate timing the post attempted",
                reviewRepository.ratings.single().responseMs,
                recorded.single().responseMs,
            )
        }

    @Test
    fun test_a_successful_rating_is_not_written_to_the_outbox() =
        runTest {
            val ratingOutbox = FakeRatingOutbox()
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = null)),
                    ratingOutbox = ratingOutbox,
                )

            viewModel.flip()
            viewModel.rate(RATING_GOOD)

            assertTrue(
                "the outbox is for failures only — a posted rating is never queued",
                ratingOutbox.recorded.isEmpty(),
            )
        }

    @Test
    fun test_queued_count_reflects_persisted_rows_not_a_local_counter() =
        runTest {
            val reviewRepository = FakeReviewRepository().apply { offline() }
            val ratingOutbox = FakeRatingOutbox()
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = null), card(id = 2, step = null)),
                    reviewRepository = reviewRepository,
                    ratingOutbox = ratingOutbox,
                )

            viewModel.flip()
            viewModel.rate(RATING_GOOD)
            assertEquals(
                "the persisted row drives the queued bar",
                1,
                viewModel.uiState.value.queuedRatingCount,
            )

            // The second rating cannot be persisted: the bar must not move.
            ratingOutbox.persistFails = true
            viewModel.flip()
            viewModel.rate(RATING_GOOD)
            assertEquals(
                "a rating that failed to persist must not inflate the count",
                1,
                viewModel.uiState.value.queuedRatingCount,
            )
        }

    @Test
    fun test_rating_carries_the_device_id() =
        runTest {
            val reviewRepository = FakeReviewRepository().apply { offline() }
            val ratingOutbox = FakeRatingOutbox()
            val viewModel =
                newViewModel(
                    cards = listOf(card(id = 1, step = null), card(id = 2, step = null)),
                    reviewRepository = reviewRepository,
                    ratingOutbox = ratingOutbox,
                )

            // Offline: the rating lands in the outbox.
            viewModel.flip()
            viewModel.rate(RATING_GOOD)
            assertEquals(
                REGISTERED_DEVICE_ID,
                ratingOutbox.recorded.single().deviceId,
            )

            // Back online: the rating lands on the post.
            reviewRepository.online { rating -> outcome(rating, step = null) }
            viewModel.flip()
            viewModel.rate(RATING_GOOD)
            assertEquals(
                "review_logs.device_id identifies the phone — a literal null fails the step 4 gate",
                REGISTERED_DEVICE_ID,
                reviewRepository.ratings.last().deviceId,
            )
        }

    // --- fakes and fixtures ---

    private fun newViewModel(
        cards: List<Card>,
        learningStepsMinutes: List<Int> = listOf(1, 10),
        clock: Clock = MutableClock(sessionStart),
        reviewRepository: FakeReviewRepository = FakeReviewRepository(),
        ratingOutbox: FakeRatingOutbox = FakeRatingOutbox(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): ReviewViewModel =
        ReviewViewModel(
            cardRepository = FakeCardRepository(cards, learningStepsMinutes),
            reviewRepository = reviewRepository,
            ratingOutbox = ratingOutbox,
            settings = settings,
            statsRepository = FakeStatsRepository(),
            clock = clock,
        )

    /** The summary sheet's next-due fetch is not under test here; it stays unanswered. */
    private class FakeStatsRepository : StatsRepository {
        override suspend fun stats(): Result<Stats> = Result.NetworkError(IOException("no stats in review tests"))
    }

    /**
     * In-memory [RatingOutbox]. [queuedCountFlow] stands in for the DAO's
     * live count: it moves only when [record] actually persists, so a failed
     * persist can never inflate the queued bar.
     */
    private class FakeRatingOutbox : RatingOutbox {
        val recorded = mutableListOf<ReviewRating>()
        val queuedCountFlow = MutableStateFlow(0)
        var persistFails = false

        override suspend fun record(rating: ReviewRating) {
            if (persistFails) throw IOException("simulated persist failure")
            recorded += rating
            queuedCountFlow.value = recorded.size
        }

        override fun queuedCount(): Flow<Int> = queuedCountFlow
    }

    /** Settings with a registered device, as after a successful `POST /devices`. */
    private class FakeSettingsRepository(
        private val deviceId: Long? = REGISTERED_DEVICE_ID,
    ) : SettingsRepository {
        override suspend fun load(): StoredConnection = StoredConnection(baseUrl = "http://test", hasApiKey = true, deviceId = deviceId)

        override suspend fun saveConnection(
            baseUrl: String,
            apiKey: String?,
        ) = Unit

        override suspend fun saveDeviceId(deviceId: Long) = Unit
    }

    private class FakeCardRepository(
        cards: List<Card>,
        learningStepsMinutes: List<Int>,
    ) : CardRepository {
        private val summary =
            DueSummary(
                dueCount = cards.size,
                newCount = 0,
                learningStepsMinutes = learningStepsMinutes,
                cards = cards,
            )

        override suspend fun dueCards(forceRefresh: Boolean): Result<DueSummary> = Result.Success(summary)

        override suspend fun refreshDueCards(): Result<DueSummary> = Result.Success(summary)

        // Card controls from 4j — not exercised by the review session fake.
        override suspend fun editCard(
            cardId: Long,
            front: String?,
            back: String?,
            tags: List<String>?,
        ): Result<Unit> = Result.Success(Unit)

        override suspend fun suspendCard(cardId: Long): Result<Instant?> = Result.Success(null)

        override suspend fun unsuspendCard(cardId: Long): Result<Instant?> = Result.Success(null)
    }

    private class FakeReviewRepository : ReviewRepository {
        val ratings = mutableListOf<ReviewRating>()
        val buryCalls = mutableListOf<Long>()
        var rateHandler: (ReviewRating) -> Result<RateOutcome> = { rating -> outcome(rating, step = null) }

        fun offline() {
            rateHandler = { Result.NetworkError(IOException("offline")) }
        }

        fun online(handler: (ReviewRating) -> Result<RateOutcome>) {
            rateHandler = handler
        }

        override suspend fun rate(rating: ReviewRating): Result<RateOutcome> {
            ratings += rating
            return rateHandler(rating)
        }

        override suspend fun bury(cardId: Long): Result<Unit> {
            buryCalls += cardId
            return Result.Success(Unit)
        }

        override suspend fun editCard(
            cardId: Long,
            front: String?,
            back: String?,
        ): Result<Unit> = Result.Success(Unit)
    }

    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        const val RATING_AGAIN = 1
        const val RATING_GOOD = 3
        const val REGISTERED_DEVICE_ID = 42L

        fun card(
            id: Long,
            step: Int?,
        ): Card =
            Card(
                id = id,
                unitId = id,
                type = "qa",
                front = "Q$id",
                back = "A$id",
                bookId = 1,
                book = "Evals for AI Engineers",
                chapter = "3. Error Analysis",
                tags = emptyList(),
                state = if (step == null) "review" else "learning",
                step = step,
                due = Instant.parse("2026-09-08T07:00:00Z"),
            )

        fun outcome(
            rating: ReviewRating,
            step: Int?,
            lapsed: Boolean = false,
            duplicate: Boolean = false,
        ): Result<RateOutcome> =
            Result.Success(
                RateOutcome(
                    cardId = rating.cardId,
                    nextDue = null,
                    state = if (step == null) "review" else "learning",
                    step = step,
                    lapsed = lapsed,
                    duplicate = duplicate,
                ),
            )
    }
}
