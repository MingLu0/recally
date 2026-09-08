package dev.recally.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dev.recally.data.local.RecallyDatabase
import dev.recally.data.remote.RecallyApi
import dev.recally.domain.repository.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

/**
 * Repository tests run against an in-memory Room database and a MockWebServer
 * (docs/android.md, "Tests") — never a real backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardRepositoryImplTest {
    private lateinit var server: MockWebServer
    private lateinit var database: RecallyDatabase
    private lateinit var cardRepository: CardRepositoryImpl
    private lateinit var deckRepository: DeckRepositoryImpl

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RecallyDatabase::class.java).build()
        val api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .client(OkHttpClient())
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(RecallyApi::class.java)
        val dispatcher = UnconfinedTestDispatcher()
        cardRepository = CardRepositoryImpl(api, database, dispatcher)
        deckRepository = DeckRepositoryImpl(api, dispatcher)
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun test_due_cards_served_from_room_when_offline() =
        runTest {
            server.enqueue(dueSummaryResponse(cardIds = listOf(101, 102)))
            val seeded = cardRepository.dueCards(forceRefresh = true)
            assertTrue("seed fetch should succeed, was $seeded", seeded is Result.Success)
            assertEquals(listOf(101L, 102L), (seeded as Result.Success).value.cards.map { it.id })

            // Network is now dead; the cache must answer instead.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val offline = cardRepository.dueCards(forceRefresh = true)
            assertTrue("offline read should succeed from Room, was $offline", offline is Result.Success)
            assertEquals(listOf(101L, 102L), (offline as Result.Success).value.cards.map { it.id })
            assertEquals(2, offline.value.dueCount)
            assertEquals(listOf(1, 10), offline.value.learningStepsMinutes)
        }

    @Test
    fun test_refresh_replaces_cached_due_cards() =
        runTest {
            server.enqueue(dueSummaryResponse(cardIds = listOf(101, 102)))
            cardRepository.dueCards(forceRefresh = true)

            server.enqueue(dueSummaryResponse(cardIds = listOf(103)))
            val refreshed = cardRepository.dueCards(forceRefresh = true)
            assertTrue(refreshed is Result.Success)
            assertEquals(listOf(103L), (refreshed as Result.Success).value.cards.map { it.id })

            // The cache itself holds exactly the new set — no appended duplicates.
            assertEquals(listOf(103L), database.dueCardDao().getAll().map { it.id })
        }

    @Test
    fun test_repository_returns_result_and_never_throws() =
        runTest {
            // Network failure with an empty cache: a Result.Failure, not an exception.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val networkFailure = cardRepository.dueCards(forceRefresh = true)
            assertTrue("expected Result.Failure, was $networkFailure", networkFailure is Result.Failure)

            // HTTP 500: also a Result.Failure carrying the status, not an exception.
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{ "status": 500, "detail": "boom" }"""))
            val serverFailure = cardRepository.dueCards(forceRefresh = true)
            assertTrue("expected Result.Failure, was $serverFailure", serverFailure is Result.Failure)
            assertEquals(500, (serverFailure as Result.Failure).httpStatus)

            // The same contract holds for the decks repository.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val decksFailure = deckRepository.decks()
            assertTrue("expected Result.Failure, was $decksFailure", decksFailure is Result.Failure)
        }

    private fun dueSummaryResponse(cardIds: List<Long>): MockResponse {
        val cards =
            cardIds.joinToString(",") { id ->
                """
                {
                  "id": $id, "unit_id": ${id + 1000}, "type": "qa",
                  "front": "front $id", "back": "back $id",
                  "book_id": 1, "book": "Evals for AI Engineers", "chapter": "3. Error Analysis",
                  "tags": ["evals"],
                  "state": "learning", "step": 0, "due": "2026-09-05T07:55:00Z"
                }
                """.trimIndent()
            }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{ "due_count": ${cardIds.size}, "new_count": 0, "learning_steps_minutes": [1, 10], "cards": [$cards] }""",
            )
    }
}
