package dev.recally.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
 * Parsing lives in [parseClozeSegments] so the never-raw-braces contract is
 * unit-testable; the composable only styles what the parser returns.
 */
data class ClozeSegment(
    val text: String,
    val isAnswer: Boolean,
)

private val CLOZE_MARKER = Regex("""\{\{c\d+::(.*?)}}""")

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
    val colors = MaterialTheme.recallyColors
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val answers = parseClozeSegments(text).filter { it.isAnswer }
    if (answers.isEmpty()) {
        Text(text = text, modifier = modifier, style = style, color = color)
        return
    }

    val answerStyle = style.copy(fontWeight = FontWeight.Bold)
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
        modifier = modifier,
        style = style,
        color = color,
        inlineContent = inlineContent,
    )
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
