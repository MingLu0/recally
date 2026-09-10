package dev.recally.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * Cloze rendering (design-system.md, "Cloze rendering"): `{{c1::answer}}`
 * renders as the answer on `primary-wash` with a 2dp `primary` bottom border,
 * never as raw braces. Unrevealed (review front) the same span is a blank of
 * equivalent width — the answer is measured and the placeholder sized to it,
 * which the spec defines as "equivalent width".
 *
 * A deletion wider than the line cannot use that placeholder: inline content
 * is an atomic box the text engine cannot break, so it ran past the card
 * border instead of wrapping (issue #181). Such an answer falls back to a
 * wrapping span — the same idiom `BookDetailScreen` already uses — which
 * keeps every word of the answer at the cost of the rounded box on the
 * wrapped run. Shortening the answer to fit is not an option (hard rule 7).
 *
 * Parsing lives in [parseClozeSegments] so the never-raw-braces contract is
 * unit-testable; the composable only styles what the parser returns.
 */
data class ClozeSegment(
    val text: String,
    val isAnswer: Boolean,
)

// Both braces are escaped on each side: Android's ICU regex engine rejects a
// bare closing `}}` that the JVM accepts, and an unescaped pattern throws
// PatternSyntaxException on device — crashing any session with a cloze card.
private val CLOZE_MARKER = Regex("""\{\{c\d+::(.*?)\}\}""")

/** The pattern text, so a JVM test can assert the escaping device parsing needs. */
internal fun clozeMarkerPatternForTest(): String = CLOZE_MARKER.pattern

/** Splits [text] into plain runs and cloze answers; the markers are consumed. */
fun parseClozeSegments(text: String): List<ClozeSegment> {
    val segments = mutableListOf<ClozeSegment>()
    var cursor = 0
    for (match in CLOZE_MARKER.findAll(text)) {
        if (match.range.first > cursor) {
            segments += ClozeSegment(text.substring(cursor, match.range.first), isAnswer = false)
        }
        segments += ClozeSegment(match.groupValues[1], isAnswer = true)
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        segments += ClozeSegment(text.substring(cursor), isAnswer = false)
    }
    return segments
}

/**
 * The text spine of a cloze card: plain runs appended as-is, answers as
 * inline-content placeholders (carrying the answer as alternate text). No raw
 * braces in either the revealed or the blank rendering — both use this.
 */
fun buildClozeAnnotatedString(text: String): AnnotatedString =
    buildAnnotatedString {
        var answerIndex = 0
        for (segment in parseClozeSegments(text)) {
            if (segment.isAnswer) {
                appendInlineContent(clozeContentId(answerIndex++), segment.text)
            } else {
                append(segment.text)
            }
        }
    }

@Composable
fun ClozeText(
    text: String,
    modifier: Modifier = Modifier,
    revealed: Boolean = true,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.recallyColors.ink,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val answers = parseClozeSegments(text).filter { it.isAnswer }
    if (answers.isEmpty()) {
        Text(text = text, modifier = modifier, style = style, color = color)
        return
    }

    val answerStyle = style.copy(fontWeight = FontWeight.Bold)

    // The available width is only known once the parent has measured, and the
    // inline placeholder has to be sized against it — measuring the answer
    // unconstrained is the bug (#181).
    BoxWithConstraints(modifier = modifier) {
        val availableWidthPx = constraints.maxWidth
        // All or nothing: one card mixing a boxed answer with a wrapped one
        // would read as two different kinds of blank. Writer emits exactly
        // one deletion anyway (writer.md:43), so this is the single answer.
        val everyAnswerFits =
            answers.all { segment ->
                fitsOnOneLine(textMeasurer, segment.text, answerStyle, availableWidthPx, density)
            }

        if (everyAnswerFits) {
            BoxedClozeText(
                text = text,
                answers = answers,
                revealed = revealed,
                style = style,
                answerStyle = answerStyle,
                color = color,
                textMeasurer = textMeasurer,
                density = density,
            )
        } else {
            WrappingClozeText(
                text = text,
                revealed = revealed,
                style = style,
                color = color,
            )
        }
    }
}

/**
 * True when [answerText] fits the line the blank would occupy. The blank's own
 * horizontal padding counts against the line, and a placeholder that exactly
 * fills the line still leaves no room for the plain text either side of it, so
 * the check is against the full available width — a one-line measurement that
 * needed no wrap.
 */
private fun fitsOnOneLine(
    textMeasurer: TextMeasurer,
    answerText: String,
    answerStyle: TextStyle,
    availableWidthPx: Int,
    density: Density,
): Boolean {
    if (availableWidthPx <= 0 || availableWidthPx == Constraints.Infinity) return true
    val paddingPx = with(density) { (CLOZE_HORIZONTAL_PADDING * 2).roundToPx() }
    val budgetPx = (availableWidthPx - paddingPx).coerceAtLeast(0)
    val measured =
        textMeasurer.measure(
            text = AnnotatedString(answerText),
            style = answerStyle,
            constraints = Constraints(maxWidth = budgetPx),
        )
    return measured.lineCount <= 1 && !measured.didOverflowWidth
}

/**
 * The spec rendering: each answer is an inline-content placeholder sized to
 * the measured answer, drawn as a rounded `primary-wash` box with a 2dp
 * `primary` bottom rule. Only used when every answer fits its line.
 */
@Composable
private fun BoxedClozeText(
    text: String,
    answers: List<ClozeSegment>,
    revealed: Boolean,
    style: TextStyle,
    answerStyle: TextStyle,
    color: Color,
    textMeasurer: TextMeasurer,
    density: Density,
) {
    val colors = MaterialTheme.recallyColors
    val inlineContent =
        answers
            .mapIndexed { index, segment ->
                val measured = textMeasurer.measure(AnnotatedString(segment.text), answerStyle)
                val width =
                    with(density) {
                        (measured.size.width.toDp() + CLOZE_HORIZONTAL_PADDING * 2).toSp()
                    }
                val height =
                    with(density) {
                        (measured.size.height.toDp() + CLOZE_VERTICAL_PADDING * 2).toSp()
                    }
                clozeContentId(index) to
                    InlineTextContent(
                        Placeholder(
                            width = width,
                            height = height,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                    ) {
                        val borderColor = colors.primary
                        Box(
                            modifier =
                                Modifier
                                    .background(
                                        if (revealed) colors.primaryWash else colors.primaryMuted,
                                        RoundedCornerShape(CLOZE_RADIUS),
                                    ).then(
                                        if (revealed) {
                                            Modifier.drawBehind {
                                                val strokeWidth = CLOZE_BORDER_WIDTH.toPx()
                                                drawLine(
                                                    color = borderColor,
                                                    start = Offset(0f, size.height - strokeWidth / 2),
                                                    end = Offset(size.width, size.height - strokeWidth / 2),
                                                    strokeWidth = strokeWidth,
                                                )
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ).padding(
                                        horizontal = CLOZE_HORIZONTAL_PADDING,
                                        vertical = CLOZE_VERTICAL_PADDING,
                                    ),
                        ) {
                            Text(
                                text = segment.text,
                                style = answerStyle,
                                color = if (revealed) colors.primary else Color.Transparent,
                            )
                        }
                    }
            }.toMap()

    Text(
        text = buildClozeAnnotatedString(text),
        style = style,
        color = color,
        inlineContent = inlineContent,
    )
}

/**
 * The fallback for a deletion too long to box: the answer is a styled run in
 * the same text flow, so the engine breaks it across lines like any other
 * text. The wash and weight survive; the rounded corners and the 2dp bottom
 * rule cannot follow a wrapped run, so revealed answers carry an underline
 * decoration in their place. Unrevealed the run is transparent on the same
 * filled wash, which is the blank — of equivalent width by construction,
 * since it *is* the answer's own layout.
 */
@Composable
private fun WrappingClozeText(
    text: String,
    revealed: Boolean,
    style: TextStyle,
    color: Color,
) {
    val colors = MaterialTheme.recallyColors
    val rendered =
        buildAnnotatedString {
            for (segment in parseClozeSegments(text)) {
                if (!segment.isAnswer) {
                    append(segment.text)
                    continue
                }
                pushStyle(
                    SpanStyle(
                        color = if (revealed) colors.primary else Color.Transparent,
                        background = if (revealed) colors.primaryWash else colors.primaryMuted,
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (revealed) TextDecoration.Underline else null,
                    ),
                )
                append(segment.text)
                pop()
            }
        }

    Text(text = rendered, style = style, color = color)
}

private val CLOZE_RADIUS = 4.dp
private val CLOZE_BORDER_WIDTH = 2.dp
private val CLOZE_HORIZONTAL_PADDING = 7.dp
private val CLOZE_VERTICAL_PADDING = 1.dp

private fun clozeContentId(index: Int): String = "cloze-$index"

@CombinedPreviews
@Composable
private fun ClozeTextRevealedPreview() {
    RecallyTheme {
        ClozeText(
            text = "The {{c1::Gulf of Specification}} is the gap between intent and instructions.",
        )
    }
}

@CombinedPreviews
@Composable
private fun ClozeTextBlankPreview() {
    RecallyTheme {
        ClozeText(
            text = "The {{c1::Gulf of Specification}} is the gap between intent and instructions.",
            revealed = false,
        )
    }
}

@CombinedPreviews
@Composable
private fun ClozeTextLongDeletionRevealedPreview() {
    RecallyTheme {
        ClozeText(text = LONG_DELETION_SAMPLE)
    }
}

@CombinedPreviews
@Composable
private fun ClozeTextLongDeletionBlankPreview() {
    RecallyTheme {
        ClozeText(text = LONG_DELETION_SAMPLE, revealed = false)
    }
}

/** The card from the screenshot on issue #126 that overflowed its border. */
private const val LONG_DELETION_SAMPLE =
    "A generative AI service wraps the model in {{c1::a system prompt that " +
        "constrains the model, enriches user prompts, and validates the " +
        "generated output before routing it back to users}}."
