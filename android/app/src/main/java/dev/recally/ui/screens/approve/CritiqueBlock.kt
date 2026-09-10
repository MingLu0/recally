package dev.recally.ui.screens.approve

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * The Critic's critique (design-system.md, "Critique block"): visually
 * subordinate to the card text — it is context, not content.
 *
 * The spec names two values outside the shared token set (`#FDF6F3` fill,
 * `#6B5A4F` body) for light mode only; dark falls back to the nearest tokens
 * (`warn-wash` fill, `warn` body), matching how the artboards render it.
 */
@Composable
fun CritiqueBlock(
    critique: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    val darkTheme = isSystemInDarkTheme()
    val fill = if (darkTheme) colors.warnWash else CRITIQUE_FILL_LIGHT
    val bodyColor = if (darkTheme) colors.warn else CRITIQUE_BODY_LIGHT
    val ruleColor = colors.accent

    Column(
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                .background(fill)
                .drawBehind {
                    drawRect(
                        color = ruleColor,
                        topLeft = Offset.Zero,
                        size = Size(RULE_WIDTH.toPx(), size.height),
                    )
                }.padding(
                    start = RecallySpacing.md,
                    top = RecallySpacing.sm,
                    bottom = RecallySpacing.sm,
                    end = RecallySpacing.md,
                ),
    ) {
        Text(
            text = "CRITIC",
            style = MaterialTheme.typography.labelSmall,
            color = colors.warn,
        )
        Text(
            text = critique,
            style = MaterialTheme.typography.bodySmall,
            color = bodyColor,
        )
    }
}

private val CRITIQUE_FILL_LIGHT = Color(0xFFFDF6F3)
private val CRITIQUE_BODY_LIGHT = Color(0xFF6B5A4F)
private val RULE_WIDTH = 3.dp

/**
 * Lines a long critique collapses to behind its disclosure (issue #150). The
 * Critic's prose is context, not content (design-system.md, "Critique
 * block"), so the clamp keeps the block shorter than the card body and the
 * card's action row reachable; four lines is enough to recognise which
 * critique this is before deciding to expand.
 */
internal const val CRITIQUE_COLLAPSED_MAX_LINES = 4

/**
 * Rendered line count of the critique body. Lets the UI tests assert the
 * clamp without depending on font metrics (issue #150).
 */
internal val CritiqueLineCountKey = SemanticsPropertyKey<Int>("CritiqueLineCount")

@CombinedPreviews
@Composable
private fun CritiqueBlockPreview() {
    RecallyTheme {
        CritiqueBlock(critique = "Potentially ambiguous term; Writer 3 rounds unresolved.")
    }
}
