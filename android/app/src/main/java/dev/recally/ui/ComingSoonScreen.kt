package dev.recally.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * Placeholder for a nav destination whose screen lands in a later roadmap
 * step. Stats is step 6a (issue #51, "Scope") but holds a bottom-nav seat from
 * step 4, so the tab says so rather than rendering an empty screen.
 */
@Composable
fun ComingSoonScreen(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize().padding(RecallySpacing.screenPadding),
    ) {
        Text(
            text = "$title arrives in a later release.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.recallyColors.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@CombinedPreviews
@Composable
private fun ComingSoonScreenPreview() {
    RecallyTheme {
        ComingSoonScreen(title = "Stats")
    }
}
