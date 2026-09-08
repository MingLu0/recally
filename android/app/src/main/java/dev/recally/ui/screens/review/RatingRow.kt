package dev.recally.ui.screens.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * The four rating tiles (docs/design/design-system.md, "Rating row"). Four
 * equal columns, 8dp gap, 66dp tall; Good is `success`-filled, the others
 * outlined with a 1.5dp border tinted from their own semantic colour.
 *
 * Interval hints appear under Again and Hard only — Good/Easy intervals come
 * from the server after the rating lands, so a projection here would be the
 * client computing scheduling state (hard rule 5, ADR-005).
 */
@Composable
fun RatingRow(
    hints: RatingHints,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
    ) {
        RatingTile(
            label = "Again",
            hint = hints.again,
            colors = ratingTileColors(RatingTileKind.Again),
            onClick = { onRate(RATING_AGAIN) },
            modifier = Modifier.weight(1f),
        )
        RatingTile(
            label = "Hard",
            hint = hints.hard,
            colors = ratingTileColors(RatingTileKind.Hard),
            onClick = { onRate(RATING_HARD) },
            modifier = Modifier.weight(1f),
        )
        RatingTile(
            label = "Good",
            hint = hints.good,
            colors = ratingTileColors(RatingTileKind.Good),
            onClick = { onRate(RATING_GOOD) },
            modifier = Modifier.weight(1f),
        )
        RatingTile(
            label = "Easy",
            hint = hints.easy,
            colors = ratingTileColors(RatingTileKind.Easy),
            onClick = { onRate(RATING_EASY) },
            modifier = Modifier.weight(1f),
        )
    }
}

private const val RATING_AGAIN = 1
private const val RATING_HARD = 2
private const val RATING_GOOD = 3
private const val RATING_EASY = 4

private enum class RatingTileKind { Again, Hard, Good, Easy }

private data class RatingTileColors(
    val fill: Color,
    val border: Color?,
    val label: Color,
)

/**
 * Tile colours are semantic and fixed (design-system.md, "Rules"). The dark
 * values are hand-tuned rather than mapped: a faint tinted fill under a
 * stronger border, so the outline still carries against `surface`.
 */
@Composable
private fun ratingTileColors(kind: RatingTileKind): RatingTileColors {
    val colors = MaterialTheme.recallyColors
    val dark = isSystemInDarkTheme()
    return when (kind) {
        RatingTileKind.Again ->
            if (dark) {
                RatingTileColors(fill = Color(0xFF2A1C1A), border = Color(0xFF6B3730), label = colors.danger)
            } else {
                RatingTileColors(fill = colors.ground, border = colors.dangerWash, label = colors.danger)
            }
        RatingTileKind.Hard ->
            if (dark) {
                RatingTileColors(fill = Color(0xFF2A2119), border = Color(0xFF6B4A2C), label = colors.warn)
            } else {
                RatingTileColors(fill = colors.ground, border = colors.warnWash, label = colors.warn)
            }
        RatingTileKind.Good ->
            if (dark) {
                RatingTileColors(fill = colors.success, border = null, label = colors.ground)
            } else {
                RatingTileColors(fill = colors.success, border = null, label = Color.White)
            }
        RatingTileKind.Easy ->
            if (dark) {
                RatingTileColors(fill = Color(0xFF16302A), border = Color(0xFF2F6B56), label = colors.primary)
            } else {
                RatingTileColors(fill = colors.ground, border = colors.primaryWash, label = colors.primary)
            }
    }
}

@Composable
private fun RatingTile(
    label: String,
    hint: String?,
    colors: RatingTileColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(66.dp),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(RecallyRadius.md),
        color = colors.fill,
        border = colors.border?.let { BorderStroke(1.5.dp, it) },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.ExtraBold,
                color = colors.label,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.recallyColors.inkFaint,
                )
            }
        }
    }
}

@CombinedPreviews
@Composable
private fun RatingRowPreview() {
    RecallyTheme {
        RatingRow(
            hints = RatingHints(again = "<1m", hard = "10m", good = null, easy = null),
            onRate = {},
        )
    }
}
