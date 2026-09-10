package dev.recally.data.repository

import dev.recally.data.remote.DeckListResponse
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.RecallyApiFactory
import dev.recally.domain.repository.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The issue #188 gate: `Result.NetworkError` must mean the network failed and
 * nothing else. The repository previously wrapped *every* non-cancellation
 * exception — a `SerializationException` from an unexpected payload included —
 * into a `NetworkError`, so a decoding fault reached the Decks screen as
 * "offline" while the device was connected.
 *
 * Runs against a MockWebServer, never a real backend (docs/android.md, "Tests").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckRepositoryImplTest {
    private lateinit var server: MockWebServer
    private lateinit var deckRepository: DeckRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        deckRepository = DeckRepositoryImpl(retrofitApi(), UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun test_io_exception_is_a_network_error() =
        runTest {
            // A real IOException out of the call — the one thing NetworkError
            // is allowed to mean. Injected rather than provoked with a socket
            // policy, because a dropped connection can also surface as an
            // empty body, which is a decoding fault and not this case.
            val failingRepository = DeckRepositoryImpl(throwingApi(IOException("no route to host")), UnconfinedTestDispatcher())

            val result = failingRepository.decks()

            assertTrue("expected NetworkError, was $result", result is Result.NetworkError)
        }

    @Test
    fun test_unexpected_exception_is_not_reported_as_a_network_error() =
        runTest {
            // A 200 whose body is missing the non-nullable `chapters` and
            // `truncated` fields — kotlinx.serialization raises a
            // MissingFieldException (a SerializationException). The device is
            // plainly reachable, so this must never be labelled "offline".
            server.enqueue(
                jsonResponse("""{ "decks": [ { "book_id": 1, "title": "Evals", "total": 48, "due": 6, "progress": 0.625 } ] }"""),
            )

            val result = deckRepository.decks()

            assertFalse(
                "a decoding failure must not be reported as a network error, was $result",
                result is Result.NetworkError,
            )
            // The repository still never throws — the failure has to surface as
            // some Result case (DeckRepositoryImpl's KDoc contract).
            assertTrue("expected UnexpectedError, was $result", result is Result.UnexpectedError)
        }

    @Test
    fun test_cancellation_is_rethrown() =
        runTest {
            val cancellingRepository =
                DeckRepositoryImpl(throwingApi(CancellationException("collector went away")), UnconfinedTestDispatcher())

            var caught: CancellationException? = null
            try {
                cancellingRepository.decks()
            } catch (cancellation: CancellationException) {
                caught = cancellation
            }

            assertTrue("cancellation must propagate, not become a Result", caught != null)
        }

    @Test
    fun test_decks_deserialize_the_documented_payload() =
        runTest {
            // The example body from docs/api-spec.md, "GET /decks", verbatim.
            server.enqueue(
                jsonResponse(
                    """{ "decks": [ { "book_id": 1, "title": "Evals for AI Engineers", "total": 48, "due": 6, "progress": 0.625, "chapters": 9, "truncated": 2 } ] }""",
                ),
            )

            val result = deckRepository.decks()

            assertTrue("expected Success, was $result", result is Result.Success)
            val deck = (result as Result.Success).data.single()
            assertEquals(1L, deck.bookId)
            assertEquals("Evals for AI Engineers", deck.title)
            assertEquals(48, deck.total)
            assertEquals(6, deck.due)
            assertEquals(0.625f, deck.progress, 0.0001f)
            assertEquals(9, deck.chapters)
            assertEquals(2, deck.truncated)
        }

    private fun retrofitApi(): RecallyApi =
        Retrofit
            .Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(RecallyApiFactory.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RecallyApi::class.java)

    /** A [RecallyApi] whose `decks()` fails with exactly [failure]. */
    private fun throwingApi(failure: Throwable): RecallyApi =
        object : RecallyApi by retrofitApi() {
            override suspend fun decks(): DeckListResponse = throw failure
        }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
