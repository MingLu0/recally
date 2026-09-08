package dev.recally.ui.screens.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.recally.ui.components.ClozeText
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyBottomInset
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.bookCoverColor
import dev.recally.ui.theme.recallyColors

/**
 * The review session screen (artboards `RcFront`/`DkFront`, `RcReview`/
 * `DkReview`; docs/android.md, *Screens → 2*). Pure composable: UiState in,
 * callbacks out.
 *
 * Front and flipped are **separate layouts**, not one layout with a hidden
 * half — before the flip the screen carries nothing that hints at the answer
 * or invites a decision, because `response_ms` is measured flip-to-rate and
 * an early cue would corrupt it.
 */
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
    onFlip: () -> Unit,
    onRate: (Int) -> Unit,
    onBury: () -> Unit,
    onStartEdit: () -> Unit,
    onDismissEdit: () -> Unit,
    onEditCard: (front: String, back: String) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.recallyColors.ground)) {
        when {
            uiState.isLoading -> ReviewLoadingSkeleton()
            uiState.card == null && uiState.summary == null ->
                ReviewLoadError(
                    errorMessage = uiState.errorMessage,
                    onRetry = onRetry,
                )
            else ->
                ReviewSessionContent(
                    uiState = uiState,
                    onFlip = onFlip,
                    onRate = onRate,
                    onBury = onBury,
                    onStartEdit = onStartEdit,
                    onClose = onClose,
                )
        }

        if (uiState.isUnauthorized) {
            UnauthorizedBanner(onOpenSettings = onOpenSettings, modifier = Modifier.align(Alignment.TopCenter))
        }

        val summary = uiState.summary
        if (summary != null) {
            // Scrim + bottom sheet (design-system.md, "Bottom sheet").
            Box(modifier = Modifier.fillMaxSize().background(ScrimColor))
            SessionSummarySheet(
                summary = summary,
                onDone = onDone,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (uiState.isEditing && uiState.card != null) {
        EditCardDialog(
            card = uiState.card,
            answer = uiState.answer.orEmpty(),
            onDismiss = onDismissEdit,
            onSave = onEditCard,
        )
    }
}

private val ScrimColor = Color(0x61141A18)

@Composable
private fun ReviewSessionContent(
    uiState: ReviewUiState,
    onFlip: () -> Unit,
    onRate: (Int) -> Unit,
    onBury: () -> Unit,
    onStartEdit: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    Column(modifier = Modifier.fillMaxSize()) {
        // Header: close, "N done · M to repeat", overflow.
        Column(
            modifier =
                Modifier.padding(
                    start = RecallySpacing.screenPadding,
                    end = RecallySpacing.screenPadding,
                    bottom = RecallySpacing.cardPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(RecallySpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "End session", tint = colors.ink)
                }
                Text(
                    text = "${uiState.doneCount} done · ${uiState.toRepeatCount} to repeat",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                OverflowMenu(
                    buryAvailable = uiState.buryAvailable,
                    editAvailable = uiState.editAvailable,
                    onBury = onBury,
                    onStartEdit = onStartEdit,
                )
            }
            SessionProgress(
                doneCount = uiState.doneCount,
                toRepeatCount = uiState.toRepeatCount,
                cardsLeft = uiState.cardsLeft,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))

        if (uiState.isOffline) {
            OfflineBar(queuedRatingCount = uiState.queuedRatingCount)
        }

        val card = uiState.card
        if (card != null) {
            Column(
                modifier =
                    Modifier
                        .weight(
                            1f,
                        ).padding(start = RecallySpacing.screenPadding, end = RecallySpacing.screenPadding, top = RecallySpacing.lg),
            ) {
                ReviewCardSurface(
                    card = card,
                    isFlipped = uiState.isFlipped,
                    answer = uiState.answer,
                    modifier = Modifier.weight(1f),
                )
            }

            // Front: the single "Show answer" action — nothing on screen may
            // invite a decision before the flip. Flipped: the rating row.
            Column(
                modifier =
                    Modifier.padding(
                        start = RecallySpacing.screenPadding,
                        end = RecallySpacing.screenPadding,
                        top = RecallySpacing.lg,
                        bottom = RecallyBottomInset,
                    ),
                verticalArrangement = Arrangement.spacedBy(RecallySpacing.md),
            ) {
                if (!uiState.isFlipped) {
                    Button(
                        onClick = onFlip,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(RecallyRadius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    ) {
                        Text(
                            text = "Show answer",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = RecallySpacing.sm),
                        )
                    }
                } else {
                    Text(
                        text = "How well did you know it?",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = colors.inkSoft,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    RatingRow(
                        hints = uiState.ratingHints ?: RatingHints(null, null, null, null),
                        onRate = onRate,
                    )
                }
            }
        }
    }
}

/**
 * The card surface (design-system.md, "Card surface"): defined by its 1dp
 * `line` border, never a shadow; sections separated by `line-soft`. Front and
 * flipped are two layouts sharing the shell.
 */
@Composable
private fun ReviewCardSurface(
    card: ReviewCardUi,
    isFlipped: Boolean,
    answer: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                .background(colors.surface, RoundedCornerShape(RecallyRadius.md)),
    ) {
        // Book strip.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RecallySpacing.lg, vertical = RecallySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RecallySpacing.md),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(width = 26.dp, height = 34.dp)
                        .background(
                            bookCoverColor(card.bookId, isSystemInDarkTheme()),
                            RoundedCornerShape(topStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 3.dp),
                        ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = card.book,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Text(
                    text = card.chapter,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.inkFaint,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = RecallySpacing.reviewCardPadding, vertical = RecallySpacing.reviewCardPadding),
            verticalArrangement = Arrangement.spacedBy(RecallySpacing.lg),
        ) {
            ClozeText(
                text = card.front,
                revealed = isFlipped,
                style = MaterialTheme.typography.headlineSmall,
            )

            if (isFlipped && answer != null) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
                Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
                    Text(
                        text = "ANSWER",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.success,
                        letterSpacing = 0.8.sp,
                    )
                    Text(
                        text = answer,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.inkMuted,
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RecallySpacing.md),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(46.dp)
                                .border(1.5.dp, colors.line, RoundedCornerShape(RecallyRadius.pill)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.inkFaint)
                    }
                    Text(
                        text = "Tap to reveal the answer",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = colors.inkFaint,
                    )
                }
            }
        }
    }
}

/** Overflow: Bury before the flip, Edit after it — each in exactly one phase. */
@Composable
private fun OverflowMenu(
    buryAvailable: Boolean,
    editAvailable: Boolean,
    onBury: () -> Unit,
    onStartEdit: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Card actions", tint = MaterialTheme.recallyColors.inkFaint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Bury until tomorrow") },
                enabled = buryAvailable,
                onClick = {
                    expanded = false
                    onBury()
                },
            )
            DropdownMenuItem(
                text = { Text("Edit card") },
                enabled = editAvailable,
                onClick = {
                    expanded = false
                    onStartEdit()
                },
            )
        }
    }
}

/** Offline state (design-system.md, "States"): review keeps working offline. */
@Composable
private fun OfflineBar(queuedRatingCount: Int) {
    val colors = MaterialTheme.recallyColors
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    colors.warnWash,
                ).padding(horizontal = RecallySpacing.screenPadding, vertical = RecallySpacing.sm),
    ) {
        Text(
            text = "Offline — $queuedRatingCount ratings queued",
            style = MaterialTheme.typography.bodySmall,
            color = colors.warn,
        )
    }
}

/** 401 state (design-system.md, "States"): a banner, never a crash. */
@Composable
private fun UnauthorizedBanner(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.dangerWash)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = RecallySpacing.screenPadding, vertical = RecallySpacing.md),
    ) {
        Text(
            text = "Check your API key in Settings",
            style = MaterialTheme.typography.bodySmall,
            color = colors.danger,
        )
    }
}

/** Loading state (design-system.md, "States"): skeletons, never spinners. */
@Composable
private fun ReviewLoadingSkeleton() {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier = Modifier.fillMaxSize().padding(RecallySpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.lg),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(colors.lineSoft, RoundedCornerShape(RecallyRadius.pill)))
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(colors.lineSoft, RoundedCornerShape(RecallyRadius.md)))
        Box(modifier = Modifier.fillMaxWidth().height(56.dp).background(colors.lineSoft, RoundedCornerShape(RecallyRadius.md)))
    }
}

@Composable
private fun ReviewLoadError(
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier = Modifier.fillMaxSize().padding(RecallySpacing.screenPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = errorMessage ?: "Couldn't load due cards",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        TextButton(onClick = onRetry) {
            Text("Retry", color = colors.primary)
        }
    }
}

/** Inline edit (ADR-008): wording only; scheduling is untouched. */
@Composable
private fun EditCardDialog(
    card: ReviewCardUi,
    answer: String,
    onDismiss: () -> Unit,
    onSave: (front: String, back: String) -> Unit,
) {
    var front by remember(card.id) { mutableStateOf(card.front) }
    var back by remember(card.id) { mutableStateOf(answer) }
    val colors = MaterialTheme.recallyColors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.ground, RoundedCornerShape(RecallyRadius.md))
                    .padding(RecallySpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(RecallySpacing.md),
        ) {
            Text(text = "Edit card", style = MaterialTheme.typography.titleSmall, color = colors.ink)
            OutlinedTextField(value = front, onValueChange = { front = it }, label = { Text("Front") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = back, onValueChange = { back = it }, label = { Text("Back") }, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = colors.inkMuted) }
                TextButton(onClick = { onSave(front, back) }) { Text("Save", color = colors.primary) }
            }
        }
    }
}

// --- Previews: one per state worth seeing in isolation (docs/android.md). ---

private val previewCard =
    ReviewCardUi(
        id = 101,
        type = "qa",
        front = "Why evaluate traces rather than individual steps?",
        bookId = 1,
        book = "Evals for AI Engineers",
        chapter = "3. Error Analysis",
    )

@CombinedPreviews
@Composable
private fun ReviewScreenFrontPreview() {
    RecallyTheme {
        ReviewScreen(
            uiState =
                ReviewUiState(
                    isLoading = false,
                    card = previewCard,
                    doneCount = 6,
                    toRepeatCount = 1,
                    cardsLeft = 5,
                ),
            onFlip = {},
            onRate = {},
            onBury = {},
            onStartEdit = {},
            onDismissEdit = {},
            onEditCard = { _, _ -> },
            onClose = {},
            onOpenSettings = {},
            onRetry = {},
            onDone = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun ReviewScreenFlippedPreview() {
    RecallyTheme {
        ReviewScreen(
            uiState =
                ReviewUiState(
                    isLoading = false,
                    card = previewCard,
                    isFlipped = true,
                    answer = "An LLM pipeline's behavior only makes sense end-to-end.",
                    ratingHints = RatingHints(again = "<1m", hard = "10m", good = null, easy = null),
                    doneCount = 6,
                    toRepeatCount = 1,
                    cardsLeft = 5,
                ),
            onFlip = {},
            onRate = {},
            onBury = {},
            onStartEdit = {},
            onDismissEdit = {},
            onEditCard = { _, _ -> },
            onClose = {},
            onOpenSettings = {},
            onRetry = {},
            onDone = {},
        )
    }
}

/** Mid-session re-queue: the repeated card is back, progress counts it. */
@CombinedPreviews
@Composable
private fun ReviewScreenRequeuePreview() {
    RecallyTheme {
        ReviewScreen(
            uiState =
                ReviewUiState(
                    isLoading = false,
                    card = previewCard.copy(front = "The {{c1::trace}} is the unit of evaluation."),
                    isFlipped = false,
                    doneCount = 8,
                    toRepeatCount = 2,
                    cardsLeft = 3,
                ),
            onFlip = {},
            onRate = {},
            onBury = {},
            onStartEdit = {},
            onDismissEdit = {},
            onEditCard = { _, _ -> },
            onClose = {},
            onOpenSettings = {},
            onRetry = {},
            onDone = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun ReviewScreenOfflinePreview() {
    RecallyTheme {
        ReviewScreen(
            uiState =
                ReviewUiState(
                    isLoading = false,
                    card = previewCard,
                    isOffline = true,
                    queuedRatingCount = 3,
                    doneCount = 4,
                    toRepeatCount = 1,
                    cardsLeft = 6,
                ),
            onFlip = {},
            onRate = {},
            onBury = {},
            onStartEdit = {},
            onDismissEdit = {},
            onEditCard = { _, _ -> },
            onClose = {},
            onOpenSettings = {},
            onRetry = {},
            onDone = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun ReviewScreenSummaryPreview() {
    RecallyTheme {
        ReviewScreen(
            uiState =
                ReviewUiState(
                    isLoading = false,
                    doneCount = 12,
                    summary =
                        SessionSummaryUi(
                            reviewedCount = 12,
                            elapsedMs = 400_000,
                            goodOrEasyCount = 9,
                            hardCount = 2,
                            againCount = 1,
                            lapseCount = 1,
                        ),
                ),
            onFlip = {},
            onRate = {},
            onBury = {},
            onStartEdit = {},
            onDismissEdit = {},
            onEditCard = { _, _ -> },
            onClose = {},
            onOpenSettings = {},
            onRetry = {},
            onDone = {},
        )
    }
}
