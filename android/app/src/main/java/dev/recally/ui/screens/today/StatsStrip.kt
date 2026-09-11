package dev.recally.ui.screens.today

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.recallyColors
import kotlin.math.roundToInt

/**
 * Stats strip (design-system.md, "Stats strip"; artboards `RcWhite.dc.html` /
 * `DarkNeutral.dc.html`). One bordered unit: a fixed 104dp streak cell with a
 * right `line` border, a three-metric grid to its right, and a full-width
 * `primary` action bar fused to the bottom inside the same border and radius
 * clip — part of the strip, not a separate button.
 *
 * The nothing-due treatment swaps the action bar for a `line`-bordered,
 * `ink-faint` one (design-system.md, "States"): "Nothing due", with
 * [nextDueLabel] appended ("Nothing due — next card in 4 hours") when the
 * server serves a `next_due_at` (issue #134).
 */
@Composable
fun StatsStrip(
    streakDays: Int?,
    reviewsToday: Int?,
    retention30d: Double?,
    newCount: Int?,
    dueCount: Int?,
    nothingDue: Boolean,
    onStartReview: () -> Unit,
    modifier: Modifier = Modifier,
    nextDueLabel: String? = null,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md)),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            StreakCell(streakDays = streakDays)
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(colors.lineSoft),
            )
            TodayMetrics(
                reviewsToday = reviewsToday,
                retention30d = retention30d,
                newCount = newCount,
                modifier = Modifier.weight(1f),
            )
        }
        ActionBar(
            dueCount = dueCount,
            newCount = newCount,
            nothingDue = nothingDue,
            nextDueLabel = nextDueLabel,
            onStartReview = onStartReview,
        )
    }
}

@Composable
private fun StreakCell(streakDays: Int?) {
    val colors = MaterialTheme.recallyColors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs),
        modifier =
            Modifier
                .width(STREAK_CELL_WIDTH)
                .padding(vertical = 13.dp, horizontal = 10.dp),
    ) {
        Text(
            text = "Streak",
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Image(
                imageVector = flameVector(),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            MetricValue(streakDays?.toString(), colors.ink)
        }
        Text(
            text = "days",
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkFaint,
        )
    }
}

@Composable
private fun TodayMetrics(
    reviewsToday: Int?,
    retention30d: Double?,
    newCount: Int?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier.padding(vertical = 13.dp, horizontal = RecallySpacing.sm),
    ) {
        Text(
            text = "Today",
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth()) {
            // Colour on numbers, not on chrome (design-system.md, "Rules").
            Metric(
                value = reviewsToday?.toString(),
                label = "reviewed",
                valueColor = colors.primary,
                modifier = Modifier.weight(1f),
            )
            Metric(
                // Null already falls through to the no-data dash in
                // `MetricValue`; the label is what says what the figure counts
                // (design-system.md, "The retention figure"; issue #190).
                value = retention30d?.let { "${(it * 100).roundToInt()}%" },
                label = "recall 30d",
                valueColor = colors.success,
                modifier = Modifier.weight(1f),
            )
            Metric(
                value = newCount?.toString(),
                label = "new",
                valueColor = colors.accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Metric(
    value: String?,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = modifier,
    ) {
        MetricValue(value, valueColor)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.recallyColors.inkFaint,
        )
    }
}

@Composable
private fun MetricValue(
    value: String?,
    color: Color,
) {
    val colors = MaterialTheme.recallyColors
    Text(
        text = value ?: "–",
        style = MaterialTheme.typography.titleMedium,
        color = if (value != null) color else colors.inkFaint,
    )
}

/**
 * The fused bottom bar. When there is nothing to review it loses the fill and
 * reads in `ink-faint` under a hairline divider (design-system.md, "States").
 */
@Composable
private fun ActionBar(
    dueCount: Int?,
    newCount: Int?,
    nothingDue: Boolean,
    nextDueLabel: String?,
    onStartReview: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    val label =
        when {
            // A null label keeps the bare treatment — never "in 0 hours".
            nothingDue && nextDueLabel != null -> "NOTHING DUE — ${nextDueLabel.uppercase()}"
            nothingDue -> "NOTHING DUE"
            (dueCount ?: 0) > 0 -> "REVIEW $dueCount ${if (dueCount == 1) "CARD" else "CARDS"} DUE"
            else -> "REVIEW $newCount NEW ${if (newCount == 1) "CARD" else "CARDS"}"
        }
    val barModifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RecallyRadius.md))
            .then(
                if (nothingDue) {
                    Modifier
                } else {
                    Modifier
                        .background(colors.primary)
                        .clickable(onClick = onStartReview)
                },
            )

    if (nothingDue) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line),
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (nothingDue) Arrangement.Center else Arrangement.SpaceBetween,
        modifier = barModifier.padding(vertical = 13.dp, horizontal = RecallySpacing.cardPadding),
    ) {
        Text(
            text = label,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                ),
            color = if (nothingDue) colors.inkFaint else Color.White,
        )
        if (!nothingDue) {
            Image(
                imageVector = chevronVector(),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * Loading skeleton in `line-soft` at the strip's real dimensions
 * (design-system.md, "States": no spinners).
 */
@Composable
fun StatsStripSkeleton(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md)),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(STREAK_CELL_WIDTH)
                    .height(SKELETON_TOP_HEIGHT)
                    .padding(13.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(RecallyRadius.sm))
                        .background(colors.lineSoft),
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(colors.lineSoft),
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(SKELETON_TOP_HEIGHT)
                    .padding(13.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(RecallyRadius.sm))
                        .background(colors.lineSoft),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(ACTION_BAR_HEIGHT)
                .background(colors.lineSoft),
        )
    }
}

/** The streak flame from the artboard, filled in `accent`. */
@Composable
private fun flameVector(): ImageVector {
    val accent = MaterialTheme.recallyColors.accent
    return remember(accent) {
        ImageVector
            .Builder(
                defaultWidth = 17.dp,
                defaultHeight = 17.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(
                pathData =
                    addPathNodes(
                        "M12 2c.6 3.4-2.2 4.6-2.2 7.8a4 4 0 0 0 8 0c0-1-.3-2-1-3 " +
                            ".3 2-1 3-1.6 2.2C14.4 7 13.6 4.2 12 2z",
                    ),
                fill = SolidColor(accent),
            ).addPath(
                pathData =
                    addPathNodes(
                        "M12 22a5 5 0 0 1-5-5c0-2.4 1.6-3.6 2.6-5.6.5 2.6 2.4 3 2.4 3" +
                            "s.4-1.6 1.4-2.6c1.4 1.6 3.6 3 3.6 5.2a5 5 0 0 1-5 5z",
                    ),
                fill = SolidColor(accent),
            ).build()
    }
}

/** The action bar's trailing chevron, stroked white per the artboard. */
@Composable
private fun chevronVector(): ImageVector {
    val strokeColor = Color.White
    return remember(strokeColor) {
        ImageVector
            .Builder(
                defaultWidth = 17.dp,
                defaultHeight = 17.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(
                pathData = addPathNodes("M9 6l6 6-6 6"),
                stroke = SolidColor(strokeColor),
                strokeLineWidth = 2.6f,
            ).build()
    }
}

private val STREAK_CELL_WIDTH = 104.dp
private val SKELETON_TOP_HEIGHT = 96.dp
private val ACTION_BAR_HEIGHT = 45.dp
