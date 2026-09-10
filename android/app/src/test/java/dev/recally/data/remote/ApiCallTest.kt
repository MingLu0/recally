package dev.recally.data.remote

import dev.recally.domain.repository.Result
import dev.recally.domain.repository.displayMessage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Version-skew gate for [apiCall] (issue #195). A backend running code older
 * than the app omits fields the DTOs require; kotlinx.serialization raises a
 * `MissingFieldException`, which is *not* an [java.io.IOException] and must
 * therefore never be reported as a connectivity problem. The message the human
 * reads has to name the real cause — a stale server — rather than sending them
 * to look at the app.
 */
class ApiCallTest {
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

    private fun api(): RecallyApi =
        RecallyApiFactory.create(
            object : ConnectionSettingsProvider {
                override fun apiKey(): String? = "test-api-key"

                override fun baseUrl(): String? = server.url("/").toString()
            },
        )

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    /** The stale-backend `GET /decks` body: no `chapters`, no `truncated`. */
    private val staleDecksBody =
        """{"decks":[{"book_id":1,"title":"Evals for AI Engineers","total":48,"due":6,"progress":0.625}]}"""

    @Test
    fun test_missing_field_is_an_unexpected_error_not_a_network_error() =
        runTest {
            enqueueJson(staleDecksBody)

            val result = apiCall { api().decks() }

            assertTrue(
                "a missing required field must map to Result.UnexpectedError, got $result",
                result is Result.UnexpectedError,
            )
            assertFalse(
                "a decoding fault must never be relabelled a network error — the server " +
                    "was plainly reached (issues #188, #195), got $result",
                result is Result.NetworkError,
            )
        }

    @Test
    fun test_missing_field_message_points_at_the_server_version() =
        runTest {
            enqueueJson(staleDecksBody)

            val result = apiCall { api().decks() }
            val message = (result as Result.UnexpectedError).displayMessage()
            val lowercased = message.lowercase()

            assertTrue(
                "the message must attribute the fault to the server being out of date, got: $message",
                lowercased.contains("out of date") || lowercased.contains("older"),
            )
            assertTrue(
                "the message must say it is the server that is out of date, got: $message",
                lowercased.contains("server") || lowercased.contains("backend"),
            )
            assertTrue(
                "the message should name the missing field so the fault is diagnosable, got: $message",
                message.contains("chapters"),
            )
            assertFalse(
                "the message must not read as a client bug the human should report, got: $message",
                lowercased.contains("unexpected response"),
            )
        }

    @Test
    fun test_unknown_server_field_still_decodes() =
        runTest {
            enqueueJson(
                """
                {"decks":[{"book_id":1,"title":"Evals for AI Engineers","total":48,"due":6,
                "progress":0.625,"chapters":9,"truncated":2,"a_field_from_a_newer_server":"ignored"}]}
                """.trimIndent(),
            )

            val result = apiCall { api().decks() }

            assertTrue(
                "an unrecognised key must be ignored, not fail the decode (ignoreUnknownKeys), got $result",
                result is Result.Success,
            )
            val decks = (result as Result.Success).data.decks
            assertTrue("the known fields must still decode", decks.single().chapters == 9)
        }
}
