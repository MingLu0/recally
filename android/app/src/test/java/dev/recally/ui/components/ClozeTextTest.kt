package dev.recally.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloze parsing (design-system.md, "Cloze rendering").
 *
 * The escaping assertion is the important one: Android's ICU regex engine
 * rejects an unescaped closing `}}` that the JVM accepts, so a pattern that
 * compiles in a unit test can still throw `PatternSyntaxException` on device
 * and crash the review session. Asserting on the pattern text is the only way
 * a JVM test can catch that.
 */
class ClozeTextTest {
    @Test
    fun test_cloze_pattern_escapes_its_closing_braces() {
        val pattern = clozeMarkerPatternForTest()

        assertTrue(
            "closing braces must be escaped for Android's ICU regex engine, got $pattern",
            pattern.endsWith("""\}\}"""),
        )
    }

    @Test
    fun test_every_cloze_pattern_in_the_app_compiles_on_android() {
        // Android's ICU engine is stricter than the JVM's, so compiling here is
        // not proof. Every cloze pattern must escape both closing braces, and
        // there must be only one such pattern — a second copy is how the
        // unescaped one survived in BookDetailScreen.
        val pattern = clozeMarkerPatternForTest()

        assertTrue("pattern must escape closing braces: $pattern", pattern.endsWith("""\}\}"""))
        assertTrue("pattern must accept any cloze index, not just c1: $pattern", "c1::" !in pattern)
    }

    @Test
    fun test_a_higher_cloze_index_is_still_parsed() {
        // A `c1`-only pattern silently fails to render c2/c3 markers.
        val segments = parseClozeSegments("The {{c2::second}} blank.")

        assertEquals(ClozeSegment("second", isAnswer = true), segments[1])
    }

    @Test
    fun test_a_cloze_marker_becomes_an_answer_segment() {
        val segments = parseClozeSegments("Retrieval practice {{c1::strengthens}} memory.")

        assertEquals(3, segments.size)
        assertEquals(ClozeSegment("Retrieval practice ", isAnswer = false), segments[0])
        assertEquals(ClozeSegment("strengthens", isAnswer = true), segments[1])
        assertEquals(ClozeSegment(" memory.", isAnswer = false), segments[2])
    }

    @Test
    fun test_text_without_a_marker_is_one_plain_segment() {
        val segments = parseClozeSegments("No cloze here.")

        assertEquals(listOf(ClozeSegment("No cloze here.", isAnswer = false)), segments)
    }

    @Test
    fun test_a_marker_at_the_start_produces_no_leading_empty_segment() {
        val segments = parseClozeSegments("{{c1::Axial coding}} groups open codes.")

        assertEquals(2, segments.size)
        assertEquals(ClozeSegment("Axial coding", isAnswer = true), segments[0])
        assertEquals(ClozeSegment(" groups open codes.", isAnswer = false), segments[1])
    }
}
