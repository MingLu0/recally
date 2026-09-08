package dev.recally.ui.screens.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyBottomInset
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.RecallyTypeScale
import dev.recally.ui.theme.recallyColors

/**
 * The session summary sheet (artboards `RcSheet` / `DkSheet`; component spec
 * in design-system.md, "Bottom sheet"). Reviewed count and elapsed time are
 * local; the rows are named by rating — never "lapses" — because whether an
 * Again is a lapse depends on state the server owns (docs/android.md,
 * *Screens → 2*).
 *
 * Scoped out (step 4g, G3/G1): the "next card due" row (no endpoint returns a
 * next-due timestamp), the pending-approvals action, and the streak band.
 */
@Composable
fun SessionSummarySheet(
    summary: SessionSummaryUi,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.ground, RoundedCornerShape(topStart = RecallyRadius.lg, topEnd = RecallyRadius.lg))
                .padding(
                    start = RecallySpacing.screenPadding,
                    end = RecallySpacing.screenPadding,
                    top = RecallySpacing.md,
                    bottom = RecallyBottomInset,
                ),
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.lg),
    ) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 38.dp, height = 4.dp)
                    .background(colors.line, RoundedCornerShape(RecallyRadius.pill)),
        )

        Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs)) {
            Text(
                text = "Session complete",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.ink,
            )
            Text(
                text = "${summary.reviewedCount} cards in ${formatElapsed(summary.elapsedMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSoft,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md)),
        ) {
            SummaryRow(
                label = "Good or Easy",
                count = summary.goodOrEasyCount,
                countColor = colors.success,
                iconBackground = colors.successWash,
                iconColor = colors.success,
                showDivider = true,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = it)
            }
            SummaryRow(
                label = "Rated Hard",
                count = summary.hardCount,
                countColor = colors.warn,
                iconBackground = colors.warnWash,
                iconColor = colors.warn,
                showDivider = true,
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = it)
            }
            SummaryRow(
                label = "Rated Again",
                count = summary.againCount,
                countColor = colors.danger,
                iconBackground = colors.dangerWash,
                iconColor = colors.danger,
                showDivider = false,
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = it)
            }
        }

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RecallyRadius.md),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
        ) {
            Text(
                text = "Done",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(vertical = RecallySpacing.sm),
            )
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    count: Int,
    countColor: androidx.compose.ui.graphics.Color,
    iconBackground: androidx.compose.ui.graphics.Color,
    iconColor: androidx.compose.ui.graphics.Color,
    showDivider: Boolean,
    icon: @Composable (tint: androidx.compose.ui.graphics.Color) -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(RecallySpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RecallySpacing.md),
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(iconBackground, RoundedCornerShape(RecallyRadius.pill)),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(19.dp)) { icon(iconColor) }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$count",
                style = RecallyTypeScale.metricSmall,
                color = countColor,
            )
        }
        if (showDivider) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
        }
    }
}

/** "6 min 40 s", or "45 s" under a minute. */
private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "$minutes min $seconds s" else "$seconds s"
}

@CombinedPreviews
@Composable
private fun SessionSummarySheetPreview() {
    RecallyTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SessionSummarySheet(
                summary =
                    SessionSummaryUi(
                        reviewedCount = 12,
                        elapsedMs = 400_000,
                        goodOrEasyCount = 9,
                        hardCount = 2,
                        againCount = 1,
                        lapseCount = 1,
                    ),
                onDone = {},
            )
        }
    }
}
