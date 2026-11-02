package dev.recally.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
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
import kotlin.math.abs

/**
 * Issue #345: a short boxed answer with a line-break opportunity (the
 * reported `/ docs`) could clip everything after the break — only `/` was
 * visible in the Approve queue although the stored card text was complete.
 *
 * The defect is a measurement/layout mismatch in [ClozeText]'s boxed
 * rendering. The placeholder is budgeted from the measured answer plus the
 * padding as one dp quantity, while `Modifier.padding` rounds each side's dp
 * value separately. At densities where `7dp` lands on a fractional pixel
 * (1.7 → 11.9, 2.8 → 19.6, 3.5 → 24.5, 3.7 → 25.9), the two roundings
 * differ and the laid-out box is a fraction of a pixel narrower than the
 * measurement allowed. The inner answer text then wraps at the break
 * opportunity, and because the placeholder height was computed for one line,
 * the second line is clipped.
 *
 * These tests pin the invariant: the inner answer stays on one line with no
 * width or height overflow at every density/font-scale combination, and the
 * blank hugs the answer's measured size rather than relying on linear
 * dp↔sp assumptions. The assertions measure the *inner* answer text, never
 * the width-constrained root (android/AGENTS.md). `@GraphicsMode(NATIVE)`
 * for the same reason as [ClozeTextTest]: legacy shadow text layout uses
 * near-constant glyph metrics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClozeTextDensityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun test_answer_with_a_line_break_opportunity_stays_on_one_line() {
        assertAnswerStaysOnOneLine(REPORTED_CARD, REPORTED_ANSWER, revealed = true, DENSITY_MATRIX)
    }

    @Test
    fun test_multi_word_answer_stays_on_one_line() {
        assertAnswerStaysOnOneLine(MULTI_WORD_CARD, MULTI_WORD_ANSWER, revealed = true, DENSITY_MATRIX)
    }

    @Test
    fun test_answer_stays_on_one_line_when_the_blank_is_unrevealed() {
        assertAnswerStaysOnOneLine(REPORTED_CARD, REPORTED_ANSWER, revealed = false, DENSITY_MATRIX)
    }

    @Test
    fun test_answer_stays_on_one_line_at_a_large_font_scale() {
        assertAnswerStaysOnOneLine(REPORTED_CARD, REPORTED_ANSWER, revealed = true, LARGE_FONT_MATRIX)
    }

    @Test
    fun test_box_hugs_the_answer_under_non_linear_font_scaling() {
        var densityOverride by mutableStateOf(HUG_MATRIX.first())
        var naturalWidthPx by mutableStateOf(-1)
        var naturalHeightPx by mutableStateOf(-1)
        composeTestRule.setContent {
            RecallyTheme {
                CompositionLocalProvider(LocalDensity provides densityOverride) {
                    // The same measurement the component uses to size the
                    // blank, captured at the active density/font scale. The
                    // laid-out inner answer must match it exactly — an
                    // implementation that sized the box from dp math at a
                    // different scale would drift from this figure.
                    val textMeasurer = rememberTextMeasurer()
                    val answerStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    val measured = textMeasurer.measure(AnnotatedString(REPORTED_ANSWER), answerStyle)
                    naturalWidthPx = measured.size.width
                    naturalHeightPx = measured.size.height
                    ScaledBox {
                        ClozeText(text = REPORTED_CARD, revealed = true)
                    }
                }
            }
        }

        for (config in HUG_MATRIX) {
            densityOverride = config
            composeTestRule.waitForIdle()

            val layout = answerLayout(REPORTED_ANSWER)
            assertFullyVisible(layout, config)
            assertTrue(
                "the blank must hug the answer's measured width at $config: " +
                    "inner ${layout.size.width}px vs measured $naturalWidthPx",
                abs(layout.size.width - naturalWidthPx) <= HUG_TOLERANCE_PX,
            )
            assertTrue(
                "the blank must hug the answer's measured height at $config: " +
                    "inner ${layout.size.height}px vs measured $naturalHeightPx",
                abs(layout.size.height - naturalHeightPx) <= HUG_TOLERANCE_PX,
            )
        }
    }

    /**
     * Renders [card] once and sweeps [matrix] by swapping the provided
     * density, asserting at every step that the inner answer text is one line
     * with no width or height overflow.
     */
    private fun assertAnswerStaysOnOneLine(
        card: String,
        answer: String,
        revealed: Boolean,
        matrix: List<Density>,
    ) {
        var densityOverride by mutableStateOf(matrix.first())
        composeTestRule.setContent {
            RecallyTheme {
                CompositionLocalProvider(LocalDensity provides densityOverride) {
                    ScaledBox {
                        ClozeText(text = card, revealed = revealed)
                    }
                }
            }
        }

        for (config in matrix) {
            densityOverride = config
            composeTestRule.waitForIdle()

            assertFullyVisible(answerLayout(answer), config)
        }
    }

    /**
     * The full-visibility invariant: the answer lays out as exactly one line
     * whose painted extents fit the blank's inner constraint — the float
     * line right/bottom vs the Int constraint, not `didOverflowWidth` (which
     * compares the paragraph's constraint width against the floored line
     * size, so it trips on any sub-pixel slack and is useless here).
     */
    private fun assertFullyVisible(
        layout: TextLayoutResult,
        config: Density,
    ) {
        val constraints = layout.layoutInput.constraints
        assertEquals("the answer must stay on one line at $config", 1, layout.lineCount)
        assertTrue(
            "the answer must not overflow the blank's width at $config: " +
                "line right ${layout.getLineRight(0)}px vs ${constraints.maxWidth}px",
            layout.getLineRight(0) <= constraints.maxWidth + FLOAT_TOLERANCE_PX,
        )
        assertTrue(
            "the answer must not overflow the blank's height at $config: " +
                "line bottom ${layout.getLineBottom(0)}px vs ${constraints.maxHeight}px",
            layout.getLineBottom(0) <= constraints.maxHeight + FLOAT_TOLERANCE_PX,
        )
    }

    /**
     * The layout result of the *inner* answer text — the `Text` node inside
     * the blank whose entire text is the answer. The outer card text is never
     * exactly the answer (the card carries surrounding words), so the exact
     * match singles this node out; the long-deletion wrapping fallback has no
     * such node at all.
     */
    private fun answerLayout(answer: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeTestRule
            .onNodeWithText(answer, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        assertTrue("the inner answer node must expose a text layout", results.isNotEmpty())
        return results.first()
    }

    /**
     * A [BOX_WIDTH] box whose width lives in dp, inside a horizontal
     * scroller. The scroller matters: the test window is a fixed px size, so
     * at swapped densities a plain dp box wider than the window would clamp
     * to it and the answer would flip into the long-deletion wrapping
     * fallback (no inner node at all). Inside the scroller the box keeps its
     * dp width — scaling with the provided density — while the text inside
     * still faces a bounded width, so the real one-line fit check runs.
     */
    @Composable
    private fun ScaledBox(content: @Composable () -> Unit) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Box(modifier = Modifier.width(BOX_WIDTH)) {
                content()
            }
        }
    }

    private companion object {
        const val REPORTED_ANSWER = "/ docs"
        const val REPORTED_CARD = "A short answer {{c1::/ docs}} with a break opportunity."

        const val MULTI_WORD_ANSWER = "retrieval practice"
        const val MULTI_WORD_CARD = "Testing yourself is {{c1::retrieval practice}}."

        /** Wide enough for every answer in this matrix at its largest scale. */
        val BOX_WIDTH = 300.dp

        /**
         * Half a pixel of slack for float round-trips between px, dp and sp.
         * The box may never be *narrower* than the measured answer; anything
         * up to this tolerance is invisible hugging on a device pixel grid.
         */
        const val HUG_TOLERANCE_PX = 0.5f

        /** Float slack for comparing painted line extents against Int constraints. */
        const val FLOAT_TOLERANCE_PX = 0.01f

        /**
         * Densities where 7dp lands on a fractional pixel (1.7 → 11.9,
         * 2.8 → 19.6, 3.5 → 24.5, 3.7 → 25.9) share the row with
         * whole-pixel densities — the defect lived only on the fractional
         * side of the matrix, so a single-density test could never see it.
         */
        val DENSITY_MATRIX =
            listOf(
                Density(1.0f, 1.0f),
                Density(1.7f, 1.0f),
                Density(2.0f, 1.0f),
                Density(2.1f, 1.0f),
                Density(2.625f, 1.0f),
                Density(2.8f, 1.0f),
                Density(3.0f, 1.0f),
                Density(3.5f, 1.0f),
                Density(3.7f, 1.0f),
                Density(4.0f, 1.0f),
                Density(2.8f, 1.3f),
                Density(3.5f, 0.85f),
            )

        val LARGE_FONT_MATRIX =
            listOf(
                Density(1.0f, 1.6f),
                Density(2.0f, 2.0f),
                Density(2.8f, 1.3f),
                Density(2.8f, 1.6f),
                Density(3.5f, 1.45f),
            )

        val HUG_MATRIX =
            listOf(
                Density(2.8f, 1.0f),
                Density(2.8f, 1.3f),
                Density(3.5f, 1.6f),
                Density(1.0f, 2.0f),
            )
    }
}
