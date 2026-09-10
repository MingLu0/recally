package dev.recally.ui.screens.approve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * Long critiques clamp to [CRITIQUE_COLLAPSED_MAX_LINES] behind a chevron
 * disclosure — the same idiom as the source highlights on this screen
 * (issue #150). Critique length is unbounded by design (the Writer ⇄ Critic
 * loop runs up to 3 rounds, AGENTS.md hard rule 9), so without the clamp a
 * 25-line critique buries the card's own Reject / Edit / Approve row. The
 * toggle only appears when the text actually overflows the clamp; a
 * one-liner renders bare.
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

    // Saveable because the card sits in a LazyColumn: per-item saveable state
    // survives the card scrolling off and back. `isClamped` is keyed on the
    // text so a replaced critique re-measures; it is derived from layout, so
    // `expanded` alone must also keep the toggle visible (collapsing back
    // must stay possible).
    var expanded by rememberSaveable { mutableStateOf(false) }
    var isClamped by rememberSaveable(critique) { mutableStateOf(false) }
    var renderedLineCount by rememberSaveable(critique) { mutableIntStateOf(0) }

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
            maxLines = if (expanded) Int.MAX_VALUE else CRITIQUE_COLLAPSED_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult ->
                renderedLineCount = layoutResult.lineCount
                if (!expanded) {
                    isClamped = layoutResult.hasVisualOverflow
                }
            },
            modifier = Modifier.semantics { critiqueLineCount = renderedLineCount },
        )
        if (expanded || isClamped) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { expanded = !expanded })
                        .padding(vertical = RecallySpacing.xs),
            ) {
                Icon(
                    imageVector =
                        if (expanded) {
                            Icons.Default.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                    contentDescription = if (expanded) "Collapse critique" else "Expand critique",
                    tint = colors.inkFaint,
                    modifier = Modifier.size(RecallySpacing.lg),
                )
                Text(
                    text = if (expanded) "Show less" else "Read full critique",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkMuted,
                )
            }
        }
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

private var SemanticsPropertyReceiver.critiqueLineCount by CritiqueLineCountKey

@CombinedPreviews
@Composable
private fun CritiqueBlockPreview() {
    RecallyTheme {
        CritiqueBlock(critique = "Potentially ambiguous term; Writer 3 rounds unresolved.")
    }
}

@CombinedPreviews
@Composable
private fun CritiqueBlockLongPreview() {
    RecallyTheme {
        CritiqueBlock(
            critique =
                (1..6).joinToString("\n\n") { round ->
                    "Round $round: the Writer kept the term ambiguous, and the Critic asked for a sharper referent each time."
                },
        )
    }
}
