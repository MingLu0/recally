package dev.recally.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Client gate for roadmap step 4c (issue #54). MockWebServer, never a real
 * backend. Payloads here are copied from docs/api-spec.md — a payload that
 * drifts from the doc is a failing test waiting to be written.
 */
class RecallyApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun apiWith(
        apiKey: String? = TEST_API_KEY,
        baseUrl: String? = server.url("/").toString(),
    ): RecallyApi =
        RecallyApiFactory.create(
            object : ConnectionSettingsProvider {
                override fun apiKey(): String? = apiKey

                override fun baseUrl(): String? = baseUrl
            },
        )

    private fun enqueueJson(
        body: String,
        code: Int = 200,
    ) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .addHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private companion object {
        const val TEST_API_KEY = "test-api-key"

        val CARD_JSON =
            """
            {"id":55,"status":"approved","type":"qa","front":"Q","back":"A",
             "original_front":"Q","original_back":"A","status_reason":null,
             "approved_at":"2026-09-08T09:00:00Z"}
            """.trimIndent()

        val RATE_RESPONSE_JSON =
            """
            {"card_id":101,"rated_at":"2026-09-04T08:12:30Z",
             "next_due":"2026-09-09T08:00:00Z","state":"review","step":null,
             "lapsed":false,"duplicate":false}
            """.trimIndent()
    }

    @Test
    fun test_api_key_header_on_every_request() =
        runTest {
            // Every endpoint in the screen → endpoint map, each with the
            // smallest response body the documented shape allows.
            val calls: List<Pair<String, suspend (RecallyApi) -> Unit>> =
                listOf(
                    """{"status":"ok"}""" to { it.healthAuth() },
                    """{"due_count":0,"new_count":0,"learning_steps_minutes":[1,10],"cards":[]}""" to
                        { it.dueCards() },
                    RATE_RESPONSE_JSON to {
                        it.rateCard(101, RateRequest(rating = 3, responseMs = 8200, ratedAt = "2026-09-04T08:12:30Z", deviceId = 3))
                    },
                    """{"results":[]}""" to {
                        it.rateBatch(
                            RateBatchRequest(
                                ratings =
                                    listOf(
                                        RateBatchItemRequest(
                                            cardId = 101,
                                            rating = 3,
                                            responseMs = 8200,
                                            ratedAt = "2026-09-04T08:12:30Z",
                                            deviceId = 3,
                                        ),
                                    ),
                            ),
                        )
                    },
                    """{"cards":[],"counts":{"pending_review":0,"needs_human":0}}""" to { it.pendingCards(status = "pending_review") },
                    CARD_JSON to { it.approveCard(55, ApproveCardRequest(front = "Q2")) },
                    CARD_JSON to { it.rejectCard(55, RejectCardRequest(reason = "trivia")) },
                    CARD_JSON to { it.editCard(55, EditCardRequest(front = "Q3")) },
                    """{"suspended_until":"2026-09-07T00:00:00+12:00"}""" to { it.buryCard(55) },
                    """{"suspended_until":"9999-12-31T00:00:00Z"}""" to { it.suspendCard(55) },
                    """{"suspended_until":null}""" to { it.unsuspendCard(55) },
                    """{"decks":[]}""" to { it.decks() },
                    """{"cards":[]}""" to { it.deckCards(1, chapter = "3. Error Analysis") },
                    """{"streak_days":9,"reviews_today":23,"retention_30d":0.87,"retention_30d_reviews":143,
                        "lapse_rate_by_type":{"qa":0.11},"lapse_rate_by_guidance_version":{"1":0.19},
                        "curation_yield":0.83,"forecast":[]}""".replace("\n", "")
                        .replace(" ", "") to { it.stats() },
                    """{"device_id":3}""" to { it.registerDevice(DeviceRequest(fcmToken = "tok", platform = "android")) },
                )

            val api = apiWith()
            for ((body, call) in calls) {
                enqueueJson(body)
                call(api)
            }

            assertEquals("every service method made exactly one request", calls.size, server.requestCount)
            repeat(calls.size) {
                val recorded = server.takeRequest()
                assertEquals(
                    "${recorded.method} ${recorded.path} must send the X-API-Key header",
                    TEST_API_KEY,
                    recorded.getHeader("X-API-Key"),
                )
            }
        }

    @Test
    fun test_due_response_parses_learning_steps_and_step() =
        runTest {
            // The docs/api-spec.md example, plus a second card in `review`
            // whose step must parse as null.
            enqueueJson(
                """
                {
                  "due_count": 12,
                  "new_count": 5,
                  "learning_steps_minutes": [1, 10],
                  "cards": [
                    {
                      "id": 101, "unit_id": 40, "type": "qa",
                      "front": "Why evaluate traces rather than individual steps?",
                      "back": "An LLM pipeline's behavior only makes sense end-to-end.",
                      "book_id": 1, "book": "Evals for AI Engineers", "chapter": "3. Error Analysis",
                      "tags": ["evals"],
                      "state": "learning", "step": 0, "due": "2026-09-05T07:55:00Z"
                    },
                    {
                      "id": 102, "unit_id": 41, "type": "cloze",
                      "front": "The {{c1::Gulf of Specification}} is the gap.",
                      "back": "—",
                      "book_id": 1, "book": "Evals for AI Engineers", "chapter": null,
                      "tags": [],
                      "state": "review", "step": null, "due": "2026-09-05T08:00:00Z"
                    }
                  ]
                }
                """.trimIndent(),
            )

            val response = apiWith().dueCards()

            assertEquals(12, response.dueCount)
            assertEquals(5, response.newCount)
            assertEquals(listOf(1, 10), response.learningStepsMinutes)

            val learningCard = response.cards[0]
            assertEquals(101, learningCard.id)
            assertEquals(40, learningCard.unitId)
            assertEquals("learning", learningCard.state)
            assertEquals(0, learningCard.step)
            assertEquals("2026-09-05T07:55:00Z", learningCard.due)
            assertEquals(listOf("evals"), learningCard.tags)

            val reviewCard = response.cards[1]
            assertEquals("review", reviewCard.state)
            assertNull("step parses as null for a card in review", reviewCard.step)
        }

    @Test
    fun test_rate_batch_results_keep_request_order() =
        runTest {
            // docs/api-spec.md: one result per request item, in request order,
            // matched by position — a failure in the middle must not shift
            // the tail.
            enqueueJson(
                """
                {
                  "results": [
                    { "card_id": 101, "rated_at": "2026-09-04T08:12:30Z", "ok": true,
                      "next_due": "2026-09-09T08:00:00Z", "state": "review", "step": null,
                      "lapsed": false, "duplicate": false },
                    { "card_id": 999, "rated_at": "2026-09-04T08:14:02Z", "ok": false,
                      "status": 404, "detail": "card not found" },
                    { "card_id": 102, "rated_at": "2026-09-04T08:15:11Z", "ok": true,
                      "next_due": "2026-09-10T08:00:00Z", "state": "review", "step": null,
                      "lapsed": true, "duplicate": false }
                  ]
                }
                """.trimIndent(),
            )

            val submitted =
                listOf(
                    RateBatchItemRequest(cardId = 101, rating = 3, responseMs = 8200, ratedAt = "2026-09-04T08:12:30Z"),
                    RateBatchItemRequest(cardId = 999, rating = 1, responseMs = 1500, ratedAt = "2026-09-04T08:14:02Z"),
                    RateBatchItemRequest(cardId = 102, rating = 2, responseMs = 4100, ratedAt = "2026-09-04T08:15:11Z"),
                )
            val response = apiWith().rateBatch(RateBatchRequest(ratings = submitted))

            assertEquals(submitted.size, response.results.size)
            for ((index, result) in response.results.withIndex()) {
                assertEquals(
                    "result $index must match the submitted rating by position",
                    submitted[index].cardId,
                    result.cardId,
                )
                assertEquals(submitted[index].ratedAt, result.ratedAt)
            }

            val failed = response.results[1]
            assertEquals(false, failed.ok)
            assertEquals(404, failed.status)
            assertEquals("card not found", failed.detail)
            assertNull("a failed item carries no next_due", failed.nextDue)

            assertEquals(true, response.results[0].ok)
            assertEquals(false, response.results[0].lapsed)
            assertEquals(true, response.results[2].lapsed)
        }

    @Test
    fun test_unknown_json_fields_are_ignored() =
        runTest {
            // A server-side G2–G6 addition must not break the client: fields
            // the DTO does not declare are ignored at every nesting level.
            enqueueJson(
                """
                {
                  "due_count": 1,
                  "new_count": 0,
                  "learning_steps_minutes": [1, 10],
                  "pending_count": 34,
                  "cards": [
                    {
                      "id": 101, "unit_id": 40, "type": "qa",
                      "front": "Q", "back": "A",
                      "book_id": 1, "book": "Evals", "chapter": "3. Error Analysis",
                      "tags": [],
                      "state": "review", "step": null, "due": "2026-09-05T07:55:00Z",
                      "next_due_at": "2026-09-09T08:00:00Z"
                    }
                  ]
                }
                """.trimIndent(),
            )

            val response = apiWith().dueCards()
            assertEquals(1, response.cards.size)
            assertEquals(101, response.cards[0].id)
        }
}
