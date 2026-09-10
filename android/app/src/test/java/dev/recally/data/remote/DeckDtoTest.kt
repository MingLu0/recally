package dev.recally.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract gate for the `GET /decks` DTOs (issue #195). A version-skewed
 * backend — one running code older than the app — omits fields the DTOs
 * require, and the intended behaviour is a loud decoding failure rather than a
 * silent zero. These tests pin both halves of that: today's documented payload
 * decodes, and the pre-#172/#173 payload raises.
 */
class DeckDtoTest {
    private val json = RecallyApiFactory.json

    /** The `GET /decks` example from docs/api-spec.md, "Decks & browsing". */
    private val currentDecksPayload =
        """
        {
          "decks": [
            {
              "book_id": 1,
              "title": "Evals for AI Engineers",
              "total": 48,
              "due": 6,
              "progress": 0.625,
              "chapters": 9,
              "truncated": 2
            }
          ]
        }
        """.trimIndent()

    /**
     * What the stale backend in issue #195 actually served: the same rows
     * without `chapters` (#172) or `truncated` (#173).
     */
    private val pre172DecksPayload =
        """
        {
          "decks": [
            {
              "book_id": 1,
              "title": "Evals for AI Engineers",
              "total": 48,
              "due": 6,
              "progress": 0.625
            }
          ]
        }
        """.trimIndent()

    @Test
    fun test_current_decks_payload_decodes() {
        val response = json.decodeFromString<DeckListResponse>(currentDecksPayload)

        assertEquals(1, response.decks.size)
        val deck = response.decks.single()
        assertEquals(1L, deck.bookId)
        assertEquals("Evals for AI Engineers", deck.title)
        assertEquals(48, deck.total)
        assertEquals(6, deck.due)
        assertEquals(0.625f, deck.progress, 0.0001f)
        assertEquals(
            "chapters must decode from the documented payload (issue #172)",
            9,
            deck.chapters,
        )
        assertEquals(
            "truncated must decode from the documented payload (issue #173)",
            2,
            deck.truncated,
        )
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun test_pre_172_decks_payload_fails_loudly() {
        val failure =
            assertThrows(
                "a payload missing chapters/truncated must raise, never decode with zeros",
                MissingFieldException::class.java,
            ) {
                json.decodeFromString<DeckListResponse>(pre172DecksPayload)
            }

        val message = failure.message.orEmpty()
        assertTrue(
            "the raise must name the missing fields, got: $message",
            message.contains("chapters") && message.contains("truncated"),
        )
    }
}
