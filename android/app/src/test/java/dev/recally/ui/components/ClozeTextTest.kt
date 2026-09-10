package dev.recally.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cloze parsing and layout (design-system.md, "Cloze rendering").
 *
 * The escaping assertion is the important one: Android's ICU regex engine
 * rejects an unescaped closing `}}` that the JVM accepts, so a pattern that
 * compiles in a unit test can still throw `PatternSyntaxException` on device
 * and crash the review session. Asserting on the pattern text is the only way
 * a JVM test can catch that.
 *
 * The layout tests cover issue #181: a deletion longer than the line was
 * rendered as an atomic inline-content placeholder sized to the *unconstrained*
 * answer width, so it overflowed the card border rather than wrapping.
 * Robolectric's legacy shadow text layout uses near-constant glyph metrics, so
 * `@GraphicsMode(NATIVE)` is required for the measurement to mean anything —
 * the same reason `ChapterHeaderTest` needs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClozeTextTest {
    @get:Rule
    val composeTestRule = createComposeRule()

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

    @Test
    fun test_long_deletion_does_not_exceed_the_available_width() {
        // The bug (#181): the blank was an atomic placeholder sized to the
        // answer measured unconstrained, so it ran past the card border.
        composeTestRule.setContent {
            FixedWidth { ClozeText(text = LONG_DELETION_CARD, revealed = true) }
        }

        assertAnswerLaidOutWithin(LONG_DELETION_CARD, LONG_DELETION_ANSWER, CARD_WIDTH)
    }

    @Test
    fun test_long_deletion_stays_within_width_when_not_revealed() {
        // The review front: transparent text on the filled wash. Same box, so
        // the same overflow — the fix has to hold in both states.
        composeTestRule.setContent {
            FixedWidth { ClozeText(text = LONG_DELETION_CARD, revealed = false) }
        }

        assertAnswerLaidOutWithin(LONG_DELETION_CARD, LONG_DELETION_ANSWER, CARD_WIDTH)
    }

    @Test
    fun test_short_deletion_layout_is_unchanged() {
        // Regression guard on the common case: a deletion that fits stays on
        // one line inside the card, unaffected by the long-deletion fix.
        composeTestRule.setContent {
            FixedWidth { ClozeText(text = SHORT_DELETION_CARD, revealed = true) }
        }

        assertAnswerLaidOutWithin(SHORT_DELETION_CARD, SHORT_DELETION_ANSWER, CARD_WIDTH)

        // The common case keeps the spec's rounded box: an inline-content
        // child node whose text is the answer and nothing else. The
        // long-deletion fallback has no such node — the answer is a span in
        // the parent — so this is what pins the two renderings apart.
        composeTestRule
            .onNodeWithText(SHORT_DELETION_ANSWER, substring = false, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun test_answer_text_is_not_truncated_in_the_string() {
        // Hard rule 7: whatever the visual fix, the card's own text is never
        // shortened. The parser must still yield the answer in full.
        val answers = parseClozeSegments(LONG_DELETION_CARD).filter { it.isAnswer }

        assertEquals(1, answers.size)
        assertEquals(LONG_DELETION_ANSWER, answers[0].text)
        assertTrue(
            "the annotated string must carry the whole answer as inline alternate text",
            buildClozeAnnotatedString(LONG_DELETION_CARD).text.contains(LONG_DELETION_ANSWER),
        )
    }

    /**
     * Asserts the rendered answer stays inside [maxWidth]. Measuring the root
     * proves nothing — it is width-constrained and cannot grow. The bug is the
     * answer escaping it: with the unconstrained inline placeholder the blank
     * laid out from 7px to a 1029px right edge inside a 300px root.
     *
     * The measurement is taken off the *outer* `Text` — the one holding the
     * whole card — because that node exists in both renderings. Boxed, the
     * answer's character range there is the inline placeholder, so its
     * bounding boxes are the blank's own extent; wrapping, they are the
     * styled run itself. Either way it is what the eye sees.
     */
    private fun assertAnswerLaidOutWithin(
        card: String,
        answer: String,
        maxWidth: Dp,
    ) {
        val (left, right) = answerHorizontalExtentPx(card, answer)
        val maxWidthPx = with(composeTestRule.density) { (maxWidth + TOLERANCE).toPx() }

        assertTrue("the cloze answer started left of the card: ${left}px", left >= -1f)
        assertTrue(
            "the cloze answer overflowed the card: right edge ${right}px in ${maxWidthPx}px",
            right <= maxWidthPx,
        )
    }

    /**
     * Leftmost and rightmost painted x of [answer] within the card's outer
     * text node. [card] is the raw `{{c1::…}}` source; the node's own text is
     * the rendered form, which never carries braces.
     */
    private fun answerHorizontalExtentPx(
        card: String,
        answer: String,
    ): Pair<Float, Float> {
        val cardText = renderedTextOf(card)
        val layout =
            composeTestRule
                .onNodeWithText(cardText, substring = false, useUnmergedTree = true)
                .fetchTextLayoutResult()

        val rendered = layout.layoutInput.text.text
        val start = rendered.indexOf(answer)
        assertTrue("the rendered text must contain the whole answer: $rendered", start >= 0)

        val boxes = (start until start + answer.length).map { layout.getBoundingBox(it) }
        return boxes.minOf { it.left } to boxes.maxOf { it.right }
    }

    /** The card as the component renders it — markers consumed, no braces. */
    private fun renderedTextOf(card: String): String = parseClozeSegments(card).joinToString("") { it.text }

    private fun SemanticsNodeInteraction.fetchTextLayoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertTrue("the node must expose a text layout", results.isNotEmpty())
        return results.first()
    }

    @Composable
    private fun FixedWidth(content: @Composable () -> Unit) {
        RecallyTheme {
            Box(modifier = Modifier.width(CARD_WIDTH)) { content() }
        }
    }

    private companion object {
        /** Narrower than the 400dp in the ticket's *Done when*, to be strict. */
        val CARD_WIDTH = 300.dp

        /** One device pixel of slack for sub-pixel text rounding. */
        val TOLERANCE = 1.dp

        // The card from the screenshot on #126.
        const val LONG_DELETION_ANSWER =
            "a system prompt that constrains the model, enriches user prompts, and " +
                "validates the generated output before routing it back to users"
        const val LONG_DELETION_CARD =
            "A generative AI service wraps the model in {{c1::$LONG_DELETION_ANSWER}}."

        const val SHORT_DELETION_ANSWER = "retrieval practice"
        const val SHORT_DELETION_CARD =
            "Testing yourself is {{c1::$SHORT_DELETION_ANSWER}}."
    }
}
