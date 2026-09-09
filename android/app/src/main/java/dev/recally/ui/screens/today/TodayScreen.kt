package dev.recally.ui.screens.today

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * Today screen (artboards `RcWhite.dc.html` / `DarkNeutral.dc.html`;
 * docs/android.md, "Screens → 1. Today"). Pure composable: UiState in,
 * callbacks out — the ViewModel lives at the route entry.
 *
 * G1 (pending counts) and G2 (per-book progress) are scoped out per issue #57,
 * so the "Waiting for you" tiles and the book rail from the artboard are not
 * built. Today works having never received a push: everything it renders comes
 * from `GET /reviews/due` and `GET /stats` (docs/android.md, "Push
 * notifications").
 */
@Composable
fun TodayScreen(
    uiState: TodayUiState,
    onStartReview: () -> Unit,
    onOpenApprove: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        WordmarkHeader()
        HorizontalDivider(color = MaterialTheme.recallyColors.lineSoft)

        if (uiState.isOffline) {
            OfflineBar()
        }
        if (uiState.showCheckSettingsBanner) {
            CheckSettingsBanner(onOpenSettings = onOpenSettings)
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(RecallySpacing.lg),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = RecallySpacing.screenPadding,
                        vertical = RecallySpacing.lg,
                    ),
        ) {
            when {
                uiState.isLoading -> StatsStripSkeleton()
                else ->
                    StatsStrip(
                        streakDays = uiState.streakDays,
                        reviewsToday = uiState.reviewsToday,
                        retention30d = uiState.retention30d,
                        newCount = uiState.newCount,
                        dueCount = uiState.dueCount,
                        nothingDue = uiState.nothingDue,
                        onStartReview = onStartReview,
                    )
            }
            ApprovalQueueRow(onOpenApprove = onOpenApprove)
            if (uiState.errorMessage != null && uiState.dueCount == null && !uiState.isLoading) {
                ErrorRow(message = uiState.errorMessage, onRetry = onRetry)
            }
        }
    }
}

/** The wordmark header from the Today artboard — no status bar is drawn. */
@Composable
private fun WordmarkHeader() {
    val colors = MaterialTheme.recallyColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RecallySpacing.screenPadding, vertical = RecallySpacing.lg),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(34.dp)
                    .background(colors.ink, RoundedCornerShape(RecallyRadius.md)),
        ) {
            Image(
                imageVector = wordmarkBookVector(),
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
        }
        Text(
            text = "Recally",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
        )
    }
}

/**
 * Entrance to the approval queue. Approve is entered from Today rather than
 * from the bottom bar (docs/android.md, "Navigation") — it is a modal task you
 * finish and leave. It carries no count: `GET /cards/pending` returns none, and
 * G1 is scoped out of step 4 rather than approximated from a list length.
 */
@Composable
private fun ApprovalQueueRow(onOpenApprove: () -> Unit) {
    val colors = MaterialTheme.recallyColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                .clickable(onClick = onOpenApprove)
                .padding(
                    horizontal = RecallySpacing.cardPadding,
                    vertical = RecallySpacing.lg,
                ),
    ) {
        Text(
            text = "Approval queue",
            style = MaterialTheme.typography.labelLarge,
            color = colors.ink,
        )
        Text(text = "›", style = MaterialTheme.typography.labelLarge, color = colors.inkFaint)
    }
}

/** Persistent bar below the app bar (design-system.md, "States"). */
@Composable
private fun OfflineBar() {
    val colors = MaterialTheme.recallyColors
    Text(
        text = "Offline — showing saved cards",
        style = MaterialTheme.typography.labelLarge,
        color = colors.warn,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.warnWash)
                .padding(horizontal = RecallySpacing.screenPadding, vertical = RecallySpacing.sm),
    )
}

/** A 401 is answered with this banner, never a crash (docs/android.md). */
@Composable
private fun CheckSettingsBanner(onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.recallyColors
    Text(
        text = "Check your API key in Settings",
        style = MaterialTheme.typography.labelLarge,
        color = colors.danger,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.dangerWash)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = RecallySpacing.screenPadding, vertical = RecallySpacing.sm),
    )
}

@Composable
private fun ErrorRow(
    message: String,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.recallyColors.inkMuted,
        )
        OutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}

/** The book glyph inside the wordmark square, stroked white per the artboard. */
@Composable
private fun wordmarkBookVector(): ImageVector {
    val strokeColor = Color.White
    return remember(strokeColor) {
        ImageVector
            .Builder(
                defaultWidth = 19.dp,
                defaultHeight = 19.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(
                pathData = addPathNodes("M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"),
                stroke = SolidColor(strokeColor),
                strokeLineWidth = 2f,
            ).addPath(
                pathData = addPathNodes("M8 8h8"),
                stroke = SolidColor(strokeColor),
                strokeLineWidth = 2f,
            ).addPath(
                pathData = addPathNodes("M8 12h6"),
                stroke = SolidColor(strokeColor),
                strokeLineWidth = 2f,
            ).build()
    }
}

@CombinedPreviews
@Composable
private fun TodayScreenLoadingPreview() {
    RecallyTheme {
        TodayScreen(
            uiState = TodayUiState(isLoading = true),
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun TodayScreenLoadedPreview() {
    RecallyTheme {
        TodayScreen(
            uiState =
                TodayUiState(
                    isLoading = false,
                    dueCount = 12,
                    newCount = 5,
                    streakDays = 9,
                    reviewsToday = 23,
                    retention30d = 0.87,
                ),
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun TodayScreenNothingDuePreview() {
    RecallyTheme {
        TodayScreen(
            uiState =
                TodayUiState(
                    isLoading = false,
                    dueCount = 0,
                    newCount = 0,
                    streakDays = 9,
                    reviewsToday = 23,
                    retention30d = 0.87,
                ),
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun TodayScreenOfflinePreview() {
    RecallyTheme {
        TodayScreen(
            uiState =
                TodayUiState(
                    isLoading = false,
                    dueCount = 12,
                    newCount = 5,
                    isOffline = true,
                ),
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun TodayScreenCheckSettingsPreview() {
    RecallyTheme {
        TodayScreen(
            uiState =
                TodayUiState(
                    isLoading = false,
                    showCheckSettingsBanner = true,
                ),
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
        )
    }
}
