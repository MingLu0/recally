package dev.recally.ui.screens.approve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * Source-highlight disclosure (design-system.md, "Source-highlight
 * disclosure"): collapsed by default behind "N source highlight(s)" — a
 * grouped unit carries several and they would otherwise dominate the card.
 *
 * The `truncated` flag is card-level in `GET /cards/pending` ("true if any
 * source highlight is clipped", docs/api-spec.md), so the warn chip is shown
 * once, atop the expanded list, rather than guessed onto individual
 * highlights. It is a flag, never a repair: nothing here reconstructs the
 * clipped text (AGENTS.md hard rule 7).
 */
@Composable
fun HighlightDisclosure(
    highlights: List<String>,
    truncated: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.recallyColors.lineSoft)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = RecallySpacing.md),
        ) {
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Default.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                contentDescription = if (expanded) "Collapse source highlights" else "Expand source highlights",
                tint = MaterialTheme.recallyColors.inkFaint,
                modifier = Modifier.size(RecallySpacing.lg),
            )
            Text(
                text = "${highlights.size} source highlight${if (highlights.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.recallyColors.inkMuted,
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
                modifier = Modifier.padding(bottom = RecallySpacing.md),
            ) {
                if (truncated) {
                    TruncatedChip()
                }
                highlights.forEach { highlight ->
                    Text(
                        text = highlight,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.recallyColors.inkMuted,
                    )
                }
            }
        }
    }
}

/** The `warn` "truncated source" badge (design-system.md, "States"). Flag only. */
@Composable
internal fun TruncatedChip(modifier: Modifier = Modifier) {
    Text(
        text = "TRUNCATED SOURCE",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.recallyColors.warn,
        modifier =
            modifier
                .background(
                    MaterialTheme.recallyColors.warnWash,
                    RoundedCornerShape(RecallyRadius.sm),
                ).padding(horizontal = RecallySpacing.sm, vertical = RecallySpacing.xs),
    )
}

@CombinedPreviews
@Composable
private fun HighlightDisclosureCollapsedPreview() {
    RecallyTheme {
        HighlightDisclosure(
            highlights = listOf("one", "two", "three"),
            truncated = false,
            expanded = false,
            onToggle = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun HighlightDisclosureExpandedPreview() {
    RecallyTheme {
        HighlightDisclosure(
            highlights =
                listOf(
                    "The Gulf of Specification is this gap between our intent and our instructions…",
                    "…where it can be executed and tested in isolation.",
                ),
            truncated = true,
            expanded = true,
            onToggle = {},
        )
    }
}
