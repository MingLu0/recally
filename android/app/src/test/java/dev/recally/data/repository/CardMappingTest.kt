package dev.recally.data.repository

import dev.recally.data.local.DueCardEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CardMappingTest {
    @Test
    fun test_entity_domain_mapping_round_trips() {
        // Nullable due and step: a card in review has step null; due must
        // survive as null too, not come back as a default.
        val withNulls =
            DueCardEntity(
                id = 101,
                unitId = 40,
                type = "qa",
                front = "Why evaluate traces rather than individual steps?",
                back = "An LLM pipeline's behavior only makes sense end-to-end.",
                bookId = 1,
                book = "Evals for AI Engineers",
                chapter = "3. Error Analysis",
                tagsJson = """["evals","fsrs"]""",
                state = "review",
                step = null,
                due = null,
            )
        assertEquals(withNulls, withNulls.toDomain().toEntity())

        val populated =
            DueCardEntity(
                id = 102,
                unitId = 41,
                type = "cloze",
                front = "The {{c1::Gulf of Specification}} is the gap between intent and instructions.",
                back = "—",
                bookId = 1,
                book = "Evals for AI Engineers",
                chapter = "1. Introduction",
                tagsJson = """[]""",
                state = "learning",
                step = 1,
                due = "2026-09-05T07:55:00Z",
            )
        assertEquals(populated, populated.toDomain().toEntity())
    }
}
