package dev.recally.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.recally.data.local.RecallyDatabase
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.RecallyApiFactory
import dev.recally.domain.model.ReviewRating
import dev.recally.domain.review.LearningStepRequeuer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant

/**
 * The step 4 Tests gate (docs/roadmap.md; ticket #60): the rating outbox, its
 * WorkManager flush against `POST /reviews/rate-batch`, and the same-session
 * re-queueing from `learning_steps_minutes` (ADR-005).
 *
 * Flush tests run against an in-memory Room database and a MockWebServer
 * (docs/android.md, "Tests") — never a real backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RatingOutboxTest {
    private lateinit var server: MockWebServer
    private lateinit var database: RecallyDatabase
    private lateinit var dao: RatingOutboxDao
    private lateinit var api: RecallyApi
    private lateinit var flusher: RatingOutboxFlusher
    private lateinit var flushScheduler: FakeFlushScheduler
    private lateinit var outbox: RatingOutbox

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RecallyDatabase::class.java).build()
        dao = database.ratingOutboxDao()
        api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .client(OkHttpClient())
                .addConverterFactory(RecallyApiFactory.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(RecallyApi::class.java)
        val dispatcher = UnconfinedTestDispatcher()
        flusher = RatingOutboxFlusher(api, dao, dispatcher)
        flushScheduler = FakeFlushScheduler()
        outbox = RoomRatingOutbox(dao, flushScheduler, dispatcher)
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    // ------------------------------------------------------------------
    // Outbox and flush
    // ------------------------------------------------------------------

    @Test
    fun test_rating_is_persisted_before_any_network_call() =
        runTest {
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:12:30Z"))

            // The row is in Room before any flush runs, and recording a rating
            // enqueues the flush worker.
            assertEquals(listOf(101L), dao.getAll().map { it.cardId })
            assertEquals(1, flushScheduler.enqueueCalls)
            assertEquals("no network call before a flush", 0, server.requestCount)

            // Simulated process death: a brand-new flusher with no shared
            // in-memory state still finds the rating and flushes it.
            val flusherAfterDeath = RatingOutboxFlusher(api, dao, UnconfinedTestDispatcher())
            server.enqueue(batchResponse(okResultJson(cardId = 101, ratedAt = "2026-09-04T08:12:30Z")))
            val result = flusherAfterDeath.flush()
            assertTrue("expected Completed, was $result", result is FlushResult.Completed)
            assertEquals(1, server.requestCount)
            assertTrue(dao.getAll().isEmpty())
        }

    @Test
    fun test_recorded_rating_survives_a_flush_failure() =
        runTest {
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:12:30Z"))

            // The connection drops before any response: nothing is acknowledged.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val first = flusher.flush()
            assertTrue("expected Retry, was $first", first is FlushResult.Retry)
            assertEquals(
                "a failed flush leaves the row in the DAO for the next attempt",
                listOf(101L),
                dao.getAll().map { it.cardId },
            )

            // The next attempt delivers the same rating and dequeues it.
            server.enqueue(batchResponse(okResultJson(cardId = 101, ratedAt = "2026-09-04T08:12:30Z")))
            val second = flusher.flush()
            assertTrue("expected Completed, was $second", second is FlushResult.Completed)
            assertTrue(dao.getAll().isEmpty())
        }

    @Test
    fun test_queued_rating_carries_client_rated_at_and_device_id() =
        runTest {
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:12:30Z", deviceId = 7L))

            val row = dao.getAll().single()
            assertEquals("2026-09-04T08:12:30Z", row.ratedAt)
            assertEquals(7L, row.deviceId)

            server.enqueue(batchResponse(okResultJson(cardId = 101, ratedAt = "2026-09-04T08:12:30Z")))
            flusher.flush()

            // The flush sends the stored values, not regenerated ones.
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"rated_at\":\"2026-09-04T08:12:30Z\""))
            assertTrue(body, body.contains("\"device_id\":7"))
        }

    @Test
    fun test_retried_flush_sends_the_same_payload() =
        runTest {
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:12:30Z"))
            outbox.record(reviewRating(cardId = 102, ratedAt = "2026-09-04T08:13:00Z"))

            // First attempt fails at the top level (500): everything stays queued.
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{ "status": 500, "detail": "boom" }"""))
            val first = flusher.flush()
            assertTrue("expected Retry, was $first", first is FlushResult.Retry)
            assertEquals(2, dao.getAll().size)

            server.enqueue(
                batchResponse(
                    okResultJson(cardId = 101, ratedAt = "2026-09-04T08:12:30Z"),
                    okResultJson(cardId = 102, ratedAt = "2026-09-04T08:13:00Z"),
                ),
            )
            val second = flusher.flush()
            assertTrue("expected Completed, was $second", second is FlushResult.Completed)

            val firstBody = server.takeRequest().body.readUtf8()
            val secondBody = server.takeRequest().body.readUtf8()
            assertEquals("a retried flush sends a byte-identical body", firstBody, secondBody)
            assertTrue(firstBody.contains("2026-09-04T08:12:30Z"))
        }

    @Test
    fun test_results_are_matched_by_position() =
        runTest {
            // Two ratings for the same card make card_id matching ambiguous;
            // only positional matching resolves the middle failure to the
            // first queued row.
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:12:30Z"))
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:13:00Z"))
            outbox.record(reviewRating(cardId = 102, ratedAt = "2026-09-04T08:14:00Z"))

            server.enqueue(
                batchResponse(
                    failedResultJson(cardId = 101, ratedAt = "2026-09-04T08:12:30Z", status = 503),
                    okResultJson(cardId = 101, ratedAt = "2026-09-04T08:13:00Z"),
                    okResultJson(cardId = 102, ratedAt = "2026-09-04T08:14:00Z"),
                ),
            )
            val result = flusher.flush()

            assertTrue("expected Retry, was $result", result is FlushResult.Retry)
            val remaining = dao.getAll()
            assertEquals("only the middle item survives", 1, remaining.size)
            assertEquals("2026-09-04T08:12:30Z", remaining.single().ratedAt)
            assertEquals(101L, remaining.single().cardId)
        }

    @Test
    fun test_ok_items_are_dequeued_including_duplicates() =
        runTest {
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:12:30Z"))
            outbox.record(reviewRating(cardId = 102, ratedAt = "2026-09-04T08:13:00Z"))

            server.enqueue(
                batchResponse(
                    okResultJson(cardId = 101, ratedAt = "2026-09-04T08:12:30Z", duplicate = false),
                    okResultJson(cardId = 102, ratedAt = "2026-09-04T08:13:00Z", duplicate = true),
                ),
            )
            val result = flusher.flush()

            assertTrue("expected Completed, was $result", result is FlushResult.Completed)
            assertEquals(2, (result as FlushResult.Completed).acked)
            assertTrue("duplicate: true is success — the row is dequeued", dao.getAll().isEmpty())
        }

    @Test
    fun test_4xx_items_are_dropped_not_retried() =
        runTest {
            outbox.record(reviewRating(cardId = 999, ratedAt = "2026-09-04T08:12:30Z"))
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:13:00Z"))

            server.enqueue(
                batchResponse(
                    failedResultJson(cardId = 999, ratedAt = "2026-09-04T08:12:30Z", status = 404),
                    okResultJson(cardId = 101, ratedAt = "2026-09-04T08:13:00Z"),
                ),
            )
            val result = flusher.flush()

            // The 404 item is dropped, not kept for a retry that could never succeed.
            assertTrue("expected Completed, was $result", result is FlushResult.Completed)
            assertEquals(1, (result as FlushResult.Completed).dropped)
            assertTrue("a 4xx failure must not stay queued", dao.getAll().isEmpty())
        }

    @Test
    fun test_5xx_items_are_kept_for_the_next_flush() =
        runTest {
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:12:30Z"))
            outbox.record(reviewRating(cardId = 102, ratedAt = "2026-09-04T08:13:00Z"))

            server.enqueue(
                batchResponse(
                    failedResultJson(cardId = 101, ratedAt = "2026-09-04T08:12:30Z", status = 503),
                    okResultJson(cardId = 102, ratedAt = "2026-09-04T08:13:00Z"),
                ),
            )
            val result = flusher.flush()

            assertTrue("expected Retry, was $result", result is FlushResult.Retry)
            assertEquals(1, (result as FlushResult.Retry).remaining)
            assertEquals(listOf(101L), dao.getAll().map { it.cardId })
        }

    @Test
    fun test_unparseable_batch_response_is_discarded() =
        runTest {
            outbox.record(reviewRating(cardId = 101, ratedAt = "2026-09-04T08:12:30Z"))
            outbox.record(reviewRating(cardId = 102, ratedAt = "2026-09-04T08:13:00Z"))

            // The body did not parse at all: a top-level 422 (problem+json).
            server.enqueue(
                MockResponse()
                    .setResponseCode(422)
                    .setHeader("Content-Type", "application/problem+json")
                    .setBody("""{ "status": 422, "detail": "body did not parse" }"""),
            )
            val result = flusher.flush()

            // The poison batch is discarded, not re-flushed forever.
            assertTrue("expected Completed, was $result", result is FlushResult.Completed)
            assertEquals(2, (result as FlushResult.Completed).dropped)
            assertTrue(dao.getAll().isEmpty())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun test_ratings_are_the_only_queued_write() {
        // Approve, reject, edit, bury, suspend and unsuspend require
        // connectivity (docs/android.md, "Offline-first sync") — the database
        // has exactly one outbox table and it can hold nothing but a rating.
        val tables = mutableListOf<String>()
        rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        val internalTables = setOf("room_master_table", "android_metadata")
        val outboxTables = tables.filterNot { it in internalTables }.filter { it.contains("outbox") }
        assertEquals("ratings are the only queued write", listOf("rating_outbox"), outboxTables)

        val columns = mutableListOf<String>()
        rawQuery("PRAGMA table_info(rating_outbox)").use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(1)
        }
        assertEquals(
            "the outbox holds a rating payload and nothing else",
            setOf("id", "cardId", "rating", "responseMs", "ratedAt", "deviceId"),
            columns.toSet(),
        )
    }

    // ------------------------------------------------------------------
    // Same-session re-queueing from learning_steps_minutes (ADR-005)
    // ------------------------------------------------------------------

    private val requeuer = LearningStepRequeuer(learningStepsMinutes = listOf(1, 10))

    @Test
    fun test_card_at_step_1_waits_the_step_1_interval() {
        val decision = requeuer.decide(currentStep = 1, rating = LearningStepRequeuer.RATING_AGAIN)
        assertEquals(10, decision?.delayMinutes)
        assertNotEquals("must not restart at the step-0 interval", 1, decision?.delayMinutes)
    }

    @Test
    fun test_offline_local_step_advances_without_a_rate_response() {
        // No server response is involved anywhere: the in-session counter is
        // advanced by the client alone (docs/android.md, ADR-005).
        val first = requeuer.decide(currentStep = 0, rating = LearningStepRequeuer.RATING_AGAIN)
        assertEquals(1, first?.delayMinutes)
        assertEquals(1, first?.nextStep)

        val second = requeuer.decide(currentStep = first!!.nextStep, rating = LearningStepRequeuer.RATING_AGAIN)
        assertEquals(10, second?.delayMinutes)
        assertEquals(2, second?.nextStep)
    }

    @Test
    fun test_offline_stops_requeueing_past_the_last_step() {
        // Step 1 is the final entry in [1, 10]; the counter it advances to is
        // past the end and must not be re-queued again.
        val last = requeuer.decide(currentStep = 1, rating = LearningStepRequeuer.RATING_AGAIN)
        assertEquals(10, last?.delayMinutes)
        assertNull(
            "past the last step the card is not re-queued",
            requeuer.decide(currentStep = last!!.nextStep, rating = LearningStepRequeuer.RATING_AGAIN),
        )
        assertNull(requeuer.decide(currentStep = 5, rating = LearningStepRequeuer.RATING_HARD))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Schema inspection via the support helper: no main-thread assertion, unlike `RoomDatabase.query`. */
    private fun rawQuery(sql: String): android.database.Cursor = database.openHelper.readableDatabase.query(sql)

    private class FakeFlushScheduler : FlushScheduler {
        var enqueueCalls = 0
            private set

        override fun enqueueAfterRating() {
            enqueueCalls += 1
        }
    }

    private fun reviewRating(
        cardId: Long,
        ratedAt: String,
        rating: Int = 3,
        responseMs: Long = 8_200L,
        deviceId: Long? = 3L,
    ) = ReviewRating(
        cardId = cardId,
        rating = rating,
        responseMs = responseMs,
        ratedAt = Instant.parse(ratedAt),
        deviceId = deviceId,
    )

    private fun okResultJson(
        cardId: Long,
        ratedAt: String,
        duplicate: Boolean = false,
    ) = """
        { "card_id": $cardId, "rated_at": "$ratedAt", "ok": true,
          "next_due": "2026-09-09T08:00:00Z", "state": "review", "step": null,
          "lapsed": false, "duplicate": $duplicate }
        """.trimIndent()

    private fun failedResultJson(
        cardId: Long,
        ratedAt: String,
        status: Int,
    ) = """
        { "card_id": $cardId, "rated_at": "$ratedAt", "ok": false,
          "status": $status, "detail": "failed with $status" }
        """.trimIndent()

    private fun batchResponse(vararg results: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{ "results": [${results.joinToString(",")}] }""")
}
