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
 * so the blank does not hint at the answer's length... it *is* the answer's
 * footprint, which the spec defines as "equivalent width".
 */
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

    val markers = CLOZE_MARKER.findAll(text).toList()
    if (markers.isEmpty()) {
        Text(text = text, modifier = modifier, style = style, color = color)
        return
    }

    val annotated =
        buildAnnotatedString {
            var lastIndex = 0
            markers.forEachIndexed { index, match ->
                append(text.substring(lastIndex, match.range.first))
                appendInlineContent(clozeContentId(index), match.groupValues[1])
                lastIndex = match.range.last + 1
            }
            append(text.substring(lastIndex))
        }

    val answerStyle = style.copy(fontWeight = FontWeight.Bold)
    val inlineContent =
        markers
            .mapIndexed { index, match ->
                val answer = match.groupValues[1]
                val measured = textMeasurer.measure(AnnotatedString(answer), answerStyle)
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
                                text = answer,
                                style = answerStyle,
                                color = if (revealed) colors.primary else Color.Transparent,
                            )
                        }
                    }
            }.toMap()

    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        inlineContent = inlineContent,
    )
}

private val CLOZE_MARKER = Regex("""\{\{c1::(.*?)}}""")

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
