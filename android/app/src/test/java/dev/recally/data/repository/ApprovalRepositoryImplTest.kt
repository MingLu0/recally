package dev.recally.data.repository

import dev.recally.data.remote.ConnectionSettingsProvider
import dev.recally.data.remote.RecallyApiFactory
import dev.recally.domain.model.DroppedUnit
import dev.recally.domain.model.DroppedUnits
import dev.recally.domain.model.PendingCard
import dev.recally.domain.repository.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The issue-#365 gate for the Dropped list's transport. MockWebServer stands in
 * for the backend so the real OkHttp/Retrofit stack, the DTOs and the mappers
 * run end to end — and so the generate call's read timeout is a real OkHttp
 * timeout, not a mocked one.
 */
class ApprovalRepositoryImplTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: ConnectionSettingsProvider
    private lateinit var repository: ApprovalRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings =
            object : ConnectionSettingsProvider {
                override fun apiKey(): String? = TEST_API_KEY

                override fun baseUrl(): String? = server.url("/").toString()
            }
        repository =
            ApprovalRepositoryImpl(
                api = RecallyApiFactory.create(settings),
                longReadApi = RecallyApiFactory.createIngest(settings),
                ioDispatcher = Dispatchers.IO,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun test_dropped_units_maps_response_to_domain() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(DROPPED_JSON))

            val result = repository.droppedUnits()

            assertEquals(
                Result.Success(
                    DroppedUnits(
                        units =
                            listOf(
                                DroppedUnit(
                                    id = 12,
                                    reason = "Sibling headings with no content.",
                                    curatedText = "Heading one. Heading two.",
                                    sourceHighlights = listOf("Heading one.", "Heading tw"),
                                    truncated = true,
                                    bookId = 3,
                                    book = "Evals for AI Engineers",
                                    chapter = "1. Introduction",
                                ),
                                DroppedUnit(
                                    id = 13,
                                    reason = null,
                                    curatedText = "A chapterless drop.",
                                    sourceHighlights = listOf("A chapterless drop."),
                                    truncated = false,
                                    bookId = 3,
                                    book = "Evals for AI Engineers",
                                    chapter = "",
                                ),
                            ),
                        count = 19,
                    ),
                ),
                result,
            )
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/units/dropped", request.path)
            assertEquals(TEST_API_KEY, request.getHeader("X-API-Key"))
        }

    @Test
    fun test_generate_uses_long_read_timeout_client() =
        runTest {
            // OkHttp's default read timeout is 10 s; the Writer ⇄ Critic loop
            // often takes 30–90 s. Headers delayed past the default prove the
            // call rides the long-read client — the default one would fail
            // with a SocketTimeoutException, i.e. a NetworkError.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(GENERATED_JSON)
                    .setHeadersDelay(DEFAULT_OKHTTP_READ_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS),
            )

            val result = repository.generateCardsForDroppedUnit(12)

            assertTrue("a slow generate must still succeed: $result", result is Result.Success)
            val cards = (result as Result.Success).data
            assertEquals(listOf(40L), cards.map(PendingCard::id))
            assertEquals(PendingCard.STATUS_PENDING_REVIEW, cards.single().status)
            assertEquals("/units/12/generate", server.takeRequest().path)
        }

    @Test
    fun test_dropped_unreachable_server_is_network_error_not_unexpected() =
        runTest {
            // Nothing is listening: a connection failure is "offline", never an
            // unexpected error (issue #188's distinction).
            server.shutdown()

            val result = repository.droppedUnits()

            assertTrue("an unreachable server is a NetworkError: $result", result is Result.NetworkError)
        }

    private companion object {
        const val TEST_API_KEY = "test-key-not-a-real-secret"
        const val DEFAULT_OKHTTP_READ_TIMEOUT_SECONDS = 10L

        val DROPPED_JSON =
            """
            {
              "count": 19,
              "units": [
                {
                  "id": 12, "reason": "Sibling headings with no content.",
                  "curated_text": "Heading one. Heading two.",
                  "source_highlights": ["Heading one.", "Heading tw"], "truncated": true,
                  "book_id": 3, "book": "Evals for AI Engineers", "chapter": "1. Introduction",
                  "created_at": "2026-09-04T09:00:00Z", "drop_resolution": null, "drop_resolved_at": null
                },
                {
                  "id": 13, "reason": null, "curated_text": "A chapterless drop.",
                  "source_highlights": ["A chapterless drop."], "truncated": false,
                  "book_id": 3, "book": "Evals for AI Engineers", "chapter": null,
                  "created_at": "2026-09-04T09:00:00Z", "drop_resolution": null, "drop_resolved_at": null
                }
              ]
            }
            """.trimIndent()

        val GENERATED_JSON =
            """
            {
              "cards": [
                {
                  "id": 40, "status": "pending_review", "type": "qa",
                  "front": "What do evals produce?", "back": "A number you can track.",
                  "status_reason": null, "source_highlights": ["Heading one."], "truncated": false,
                  "book_id": 3, "book": "Evals for AI Engineers", "chapter": "1. Introduction"
                }
              ]
            }
            """.trimIndent()
    }
}
