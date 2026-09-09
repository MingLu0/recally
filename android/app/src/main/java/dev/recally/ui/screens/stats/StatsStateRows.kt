package dev.recally.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

// The Stats screen's state rows (design-system.md, "States"): the offline
// bar, the 401 banner, an error row with retry, and the loading skeleton
// block. Package-local copies of the decks set, with copy tuned to a screen
// that caches nothing and has no queued writes.

/**
 * Persistent bar below the header. Stats are never cached
 * (docs/android.md, "Offline-first sync"), so there is nothing to browse
 * offline — the bar says so.
 */
@Composable
internal fun OfflineBar() {
    val colors = MaterialTheme.recallyColors
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .background(colors.warnWash)
                .padding(RecallySpacing.md),
    ) {
        Text(
            "Offline — stats aren't cached; reconnect to load them",
            style = MaterialTheme.typography.bodySmall,
            color = colors.warn,
        )
    }
}

/** A 401 is answered with a banner that opens Settings, never a crash. */
@Composable
internal fun UnauthorizedBanner(onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.recallyColors
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .background(colors.dangerWash)
                .clickable(onClick = onOpenSettings)
                .padding(RecallySpacing.md),
    ) {
        Text(
            "Check your API key in Settings",
            style = MaterialTheme.typography.bodySmall,
            color = colors.danger,
        )
    }
}

@Composable
internal fun ErrorRow(
    message: String,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = RecallySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text("Retry", style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

/** Skeleton block at the real component's dimensions — never a spinner. */
@Composable
internal fun SkeletonBlock(height: Dp) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(RecallyRadius.md))
                .background(MaterialTheme.recallyColors.lineSoft),
    )
}

@CombinedPreviews
@Composable
private fun StatsStateRowsPreview() {
    RecallyTheme {
        Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
            OfflineBar()
            UnauthorizedBanner(onOpenSettings = {})
            ErrorRow(message = "Server said no", onRetry = {})
            SkeletonBlock(height = 72.dp)
        }
    }
}
