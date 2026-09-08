package dev.recally.ui.screens.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.recally.domain.model.DeckCard
import dev.recally.ui.components.parseClozeSegments
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors
import java.time.Instant

/**
 * Book detail (docs/design/RcBook.dc.html): chapters expand in place rather
 * than pushing a third screen — one book has 30 chapters, so a deeper nav
 * level would be tedious (docs/android.md, Screens → 4). Cards are
 * read-only for review actions; the only writes are the ADR-008 controls
 * (edit, suspend, unsuspend).
 *
 * Expanding a chapter issues the server-side `?chapter=` filter; the rows
 * render in a lazy list so a ~1000-card book never materialises every row.
 * Scoped out until G5/G6 land: per-card state/due badges and the truncated
 * badge.
 */
@Composable
fun BookDetailScreen(
    uiState: DecksUiState,
    onBack: () -> Unit,
    onChapterToggled: (String) -> Unit,
    onStartEdit: (DeckCard) -> Unit,
    onDismissEdit: () -> Unit,
    onSubmitEdit: (String, String) -> Unit,
    onSuspendCard: (Long) -> Unit,
    onUnsuspendCard: (Long) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.ground)
                .padding(horizontal = RecallySpacing.screenPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                style = MaterialTheme.typography.titleLarge,
                color = colors.inkStrong,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(RecallyRadius.md))
                        .clickable(onClick = onBack)
                        .padding(horizontal = RecallySpacing.sm, vertical = RecallySpacing.xs),
            )
            Text(
                "Decks",
                style = MaterialTheme.typography.labelLarge,
                color = colors.inkFaint,
            )
        }
        Text(
            uiState.bookTitle ?: "Book",
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
        )
        Text(
            "${uiState.chapters.sumOf { it.cardCount }} cards · read-only",
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(RecallySpacing.md))

        if (uiState.isOffline) OfflineBar()
        if (uiState.isUnauthorized) UnauthorizedBanner(onOpenSettings)
        uiState.errorMessage?.let { ErrorRow(message = it, onRetry = onRetry) }

        if (uiState.isLoading && uiState.chapters.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
                repeat(4) { SkeletonBlock(height = 48.dp) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
                items(uiState.chapters, key = { it.name }) { chapter ->
                    ChapterSection(
                        chapter = chapter,
                        expanded = uiState.expandedChapter == chapter.name,
                        cards = if (uiState.expandedChapter == chapter.name) uiState.expandedCards else emptyList(),
                        isLoading = uiState.isChapterLoading && uiState.expandedChapter == chapter.name,
                        controlsEnabled = uiState.cardControlsEnabled,
                        onToggle = { onChapterToggled(chapter.name) },
                        onStartEdit = onStartEdit,
                        onSuspendCard = onSuspendCard,
                        onUnsuspendCard = onUnsuspendCard,
                    )
                }
            }
        }
    }

    uiState.editingCard?.let { editing ->
        EditCardDialog(
            card = editing,
            saveEnabled = uiState.cardControlsEnabled,
            onDismiss = onDismissEdit,
            onSubmit = onSubmitEdit,
        )
    }
}

@Composable
private fun ChapterSection(
    chapter: ChapterSummary,
    expanded: Boolean,
    cards: List<DeckCard>,
    isLoading: Boolean,
    controlsEnabled: Boolean,
    onToggle: () -> Unit,
    onStartEdit: (DeckCard) -> Unit,
    onSuspendCard: (Long) -> Unit,
    onUnsuspendCard: (Long) -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(RecallyRadius.md))
                    .clickable(onClick = onToggle)
                    .padding(vertical = RecallySpacing.md, horizontal = RecallySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelLarge,
                color = colors.inkFaint,
            )
            Spacer(Modifier.padding(horizontal = RecallySpacing.xs))
            Text(
                chapter.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (expanded) colors.ink else colors.inkStrong,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${chapter.cardCount} cards",
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkFaint,
            )
        }
        if (expanded) {
            when {
                isLoading -> SkeletonBlock(height = 96.dp)
                else ->
                    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
                        cards.forEach { card ->
                            BrowseCardRow(
                                card = card,
                                controlsEnabled = controlsEnabled,
                                onEdit = { onStartEdit(card) },
                                onSuspend = { onSuspendCard(card.id) },
                                onUnsuspend = { onUnsuspendCard(card.id) },
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun BrowseCardRow(
    card: DeckCard,
    controlsEnabled: Boolean,
    onEdit: () -> Unit,
    onSuspend: () -> Unit,
    onUnsuspend: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                .padding(RecallySpacing.cardPadding),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
            Badge(
                text = if (card.type == "cloze") "CLOZE" else "Q&A",
                fill = colors.neutralWash,
                textColor = colors.inkMuted,
            )
            if (card.isSuspended) {
                Badge(text = "SUSPENDED", fill = colors.warnWash, textColor = colors.warn)
            }
        }
        Spacer(Modifier.height(RecallySpacing.sm))
        Text(
            clozeRendered(card.front),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
        if (card.back.isNotBlank() && card.back != "—") {
            Spacer(Modifier.height(RecallySpacing.xs))
            Text(card.back, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onEdit, enabled = controlsEnabled) {
                Text("Edit", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = if (card.isSuspended) onUnsuspend else onSuspend,
                enabled = controlsEnabled,
            ) {
                Text(
                    if (card.isSuspended) "Unsuspend" else "Suspend",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Cloze fronts never show raw braces (design-system.md, "Cloze rendering"):
 * `{{c1::answer}}` renders as the answer on `primary-wash`, weight 700,
 * `primary` text. Browse shows the revealed form — there is no flip here.
 *
 * Splitting is [parseClozeSegments], shared with the review session: a second
 * copy of the pattern here previously matched only `c1` and left its closing
 * braces unescaped, which crashes on Android's stricter ICU regex engine.
 */
@Composable
private fun clozeRendered(front: String): AnnotatedString {
    val colors = MaterialTheme.recallyColors
    return buildAnnotatedString {
        for (segment in parseClozeSegments(front)) {
            if (!segment.isAnswer) {
                append(segment.text)
                continue
            }
            pushStyle(
                SpanStyle(
                    color = colors.primary,
                    background = colors.primaryWash,
                    fontWeight = FontWeight.Bold,
                ),
            )
            append(segment.text)
            pop()
        }
    }
}

@Composable
private fun EditCardDialog(
    card: DeckCard,
    saveEnabled: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var front by remember { mutableStateOf(card.front) }
    var back by remember { mutableStateOf(card.back) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit card", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text("Front") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text("Back") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Scheduling is untouched (ADR-008)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.recallyColors.inkFaint,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(front, back) }, enabled = saveEnabled) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun sampleCard(
    id: Long,
    suspended: Boolean = false,
) = DeckCard(
    id = id,
    type = if (id % 2L == 0L) "cloze" else "qa",
    front =
        if (id % 2L ==
            0L
        ) {
            "Error analysis starts by reading {{c1::traces}}, not metrics."
        } else {
            "Why evaluate traces rather than individual steps?"
        },
    back = if (id % 2L == 0L) "—" else "An LLM pipeline's behavior only makes sense end-to-end.",
    chapter = "3. Error Analysis",
    tags = emptyList(),
    suspendedUntil = if (suspended) Instant.parse("9999-12-31T00:00:00Z") else null,
)

private fun sampleDetailState(
    expanded: Boolean = true,
    isOffline: Boolean = false,
    isLoading: Boolean = false,
) = DecksUiState(
    isBookDetail = true,
    bookId = 1,
    bookTitle = "Evals for AI Engineers",
    isLoading = isLoading,
    isOffline = isOffline,
    chapters =
        listOf(
            ChapterSummary(name = "3. Error Analysis", cardCount = 7),
            ChapterSummary(name = "4. Evaluators", cardCount = 6),
            ChapterSummary(name = "5. Datasets", cardCount = 4),
        ),
    expandedChapter = if (expanded) "3. Error Analysis" else null,
    expandedCards = if (expanded) listOf(sampleCard(1), sampleCard(2), sampleCard(3, suspended = true)) else emptyList(),
)

@CombinedPreviews
@Composable
private fun BookDetailExpandedPreview() {
    RecallyTheme {
        BookDetailScreen(
            uiState = sampleDetailState(),
            onBack = {},
            onChapterToggled = {},
            onStartEdit = {},
            onDismissEdit = {},
            onSubmitEdit = { _, _ -> },
            onSuspendCard = {},
            onUnsuspendCard = {},
            onRetry = {},
            onOpenSettings = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun BookDetailLoadingPreview() {
    RecallyTheme {
        BookDetailScreen(
            uiState = DecksUiState(isBookDetail = true, bookId = 1, isLoading = true),
            onBack = {},
            onChapterToggled = {},
            onStartEdit = {},
            onDismissEdit = {},
            onSubmitEdit = { _, _ -> },
            onSuspendCard = {},
            onUnsuspendCard = {},
            onRetry = {},
            onOpenSettings = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun BookDetailOfflinePreview() {
    RecallyTheme {
        BookDetailScreen(
            uiState = sampleDetailState(isOffline = true),
            onBack = {},
            onChapterToggled = {},
            onStartEdit = {},
            onDismissEdit = {},
            onSubmitEdit = { _, _ -> },
            onSuspendCard = {},
            onUnsuspendCard = {},
            onRetry = {},
            onOpenSettings = {},
        )
    }
}
