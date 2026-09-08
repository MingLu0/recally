package dev.recally.data.remote

import dev.recally.domain.repository.Result
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Error-mapping gate for roadmap step 4c (issue #54): problem+json bodies map
 * into the sealed [Result] type, and a 401 is its own case — the UI shows a
 * "check settings" banner and must never crash.
 */
class ApiErrorMappingTest {
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

    private fun enqueueProblem(
        code: Int,
        detail: String,
    ) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .addHeader("Content-Type", "application/problem+json")
                .setBody("""{"status":$code,"detail":"$detail"}"""),
        )
    }

    @Test
    fun test_401_maps_to_a_distinct_unauthorized_result() =
        runTest {
            enqueueProblem(401, "invalid API key")

            val result = apiCall { api().dueCards() }

            assertTrue(
                "401 must map to Result.Unauthorized, got $result",
                result is Result.Unauthorized,
            )
            assertTrue(
                "Unauthorized must be distinguishable from a network failure",
                result !is Result.NetworkError,
            )
            assertTrue(
                "Unauthorized must be distinguishable from a generic HTTP error",
                result !is Result.HttpError,
            )

            // Contrast case: an unreachable server is a network failure, not
            // an auth failure — the Settings screen gives each its own message.
            server.shutdown()
            val unreachable = apiCall { api().dueCards() }
            assertTrue(
                "connection failure must map to Result.NetworkError, got $unreachable",
                unreachable is Result.NetworkError,
            )
        }

    @Test
    fun test_problem_json_detail_is_preserved() =
        runTest {
            enqueueProblem(422, "reason must not be blank")

            val result = apiCall { api().rejectCard(55, RejectCardRequest(reason = "")) }

            assertTrue("422 must map to Result.HttpError, got $result", result is Result.HttpError)
            val error = result as Result.HttpError
            assertEquals(422, error.status)
            assertEquals(
                "the problem+json detail must surface verbatim, not a generic message",
                "reason must not be blank",
                error.detail,
            )
        }
}
