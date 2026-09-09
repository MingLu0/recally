package dev.recally.ui.screens.approve

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.recally.domain.model.PendingCard
import dev.recally.ui.components.ClozeText
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * Approval queue screen (artboards `RcApprove.dc.html` / `DkApprove.dc.html`;
 * docs/android.md, "Screens → 3"). Pure composable: UiState in, callbacks
 * out — the ViewModel lives at the route entry.
 *
 * The header carries the collection-wide "N pending" from `GET
 * /cards/pending`'s `counts` (G1, issue #132). Still scoped out: the bulk
 * "Approve N ready" bar (G4) — cards are approved individually, and
 * `needs_human` cards are opened individually regardless (hard rule 1).
 */
@Composable
fun ApproveScreen(
    uiState: ApproveUiState,
    onFilterChange: (QueueFilter) -> Unit,
    onToggleHighlights: (Long) -> Unit,
    onApproveCard: (Long) -> Unit,
    onStartEdit: (Long) -> Unit,
    onDismissEdit: () -> Unit,
    onEditCard: (Long, String, String) -> Unit,
    onRejectCard: (Long, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ApproveHeader(pendingCount = uiState.pendingCount, onNavigateBack = onNavigateBack)
        FilterRow(filter = uiState.filter, onFilterChange = onFilterChange)
        HorizontalDivider(color = MaterialTheme.recallyColors.lineSoft)

        if (uiState.isUnauthorized) {
            UnauthorizedBanner(onOpenSettings = onOpenSettings)
        }
        if (uiState.isOffline) {
            OfflineRow(onRetry = onRetry)
        }

        when {
            uiState.isLoading -> LoadingSkeletons()
            uiState.isQueueClear && !uiState.isOffline -> QueueClear()
            else ->
                QueueList(
                    uiState = uiState,
                    onToggleHighlights = onToggleHighlights,
                    onApproveCard = onApproveCard,
                    onStartEdit = onStartEdit,
                    onDismissEdit = onDismissEdit,
                    onEditCard = onEditCard,
                    onRejectCard = onRejectCard,
                )
        }

        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.recallyColors.danger,
                modifier = Modifier.padding(horizontal = RecallySpacing.screenPadding),
            )
        }
    }
}

@Composable
private fun ApproveHeader(
    pendingCount: Int?,
    onNavigateBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.md),
        modifier =
            Modifier.padding(
                horizontal = RecallySpacing.screenPadding,
                vertical = RecallySpacing.cardPadding,
            ),
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.recallyColors.ink,
            )
        }
        Text(
            text = "Approve",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.recallyColors.ink,
        )
        if (pendingCount != null) {
            Text(
                text = "$pendingCount pending",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.recallyColors.inkMuted,
            )
        }
    }
}

/** All / Needs you (docs/android.md, "Screens → 3"). The count lives in the header, not on the chips. */
@Composable
private fun FilterRow(
    filter: QueueFilter,
    onFilterChange: (QueueFilter) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
        modifier =
            Modifier.padding(
                horizontal = RecallySpacing.screenPadding,
                vertical = RecallySpacing.sm,
            ),
    ) {
        QueueFilter.entries.forEach { option ->
            FilterChip(
                selected = filter == option,
                onClick = { onFilterChange(option) },
                label = {
                    Text(
                        text =
                            when (option) {
                                QueueFilter.ALL -> "All"
                                QueueFilter.NEEDS_YOU -> "Needs you"
                            },
                    )
                },
                shape = RoundedCornerShape(RecallyRadius.sm),
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.recallyColors.ground,
                        labelColor = MaterialTheme.recallyColors.inkMuted,
                        selectedContainerColor = MaterialTheme.recallyColors.primary,
                        selectedLabelColor = MaterialTheme.recallyColors.ground,
                    ),
                border =
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = filter == option,
                        borderColor = MaterialTheme.recallyColors.line,
                    ),
            )
        }
    }
}

/** 401: "Check your API key in Settings", tapping opens Settings (design-system.md, "States"). */
@Composable
private fun UnauthorizedBanner(onOpenSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.recallyColors.dangerWash)
                .padding(horizontal = RecallySpacing.screenPadding, vertical = RecallySpacing.md),
    ) {
        Text(
            text = "Couldn't authenticate — check your API key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.recallyColors.danger,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenSettings) {
            Text(
                text = "Open Settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.recallyColors.danger,
            )
        }
    }
}

/**
 * The queue requires connectivity (docs/android.md, "Offline-first sync"), so
 * offline it is disabled with an explanatory row rather than queued.
 */
@Composable
private fun OfflineRow(onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.recallyColors.warnWash)
                .padding(horizontal = RecallySpacing.screenPadding, vertical = RecallySpacing.md),
    ) {
        Text(
            text = "Offline — approving needs a connection. Approve, reject and edit are disabled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.recallyColors.warn,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(
                text = "Retry",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.recallyColors.warn,
            )
        }
    }
}

/** Skeleton blocks at the real components' dimensions — no spinners (design-system.md, "States"). */
@Composable
private fun LoadingSkeletons() {
    Column(
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.lg),
        modifier = Modifier.padding(RecallySpacing.screenPadding),
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = SKELETON_HEADER_WIDTH, height = RecallySpacing.lg)
                    .clip(RoundedCornerShape(RecallyRadius.sm))
                    .background(MaterialTheme.recallyColors.lineSoft),
        )
        repeat(2) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SKELETON_CARD_HEIGHT)
                        .clip(RoundedCornerShape(RecallyRadius.md))
                        .background(MaterialTheme.recallyColors.lineSoft),
            )
        }
    }
}

/** Queue drained: centred success check, "Queue clear" (design-system.md, "States"). */
@Composable
private fun QueueClear() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.md, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().padding(RecallySpacing.screenPadding),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(QUEUE_CLEAR_ICON_HALO)
                    .background(MaterialTheme.recallyColors.successWash, CircleShape),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.recallyColors.success,
            )
        }
        Text(
            text = "Queue clear",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.recallyColors.ink,
        )
    }
}

@Composable
private fun QueueList(
    uiState: ApproveUiState,
    onToggleHighlights: (Long) -> Unit,
    onApproveCard: (Long) -> Unit,
    onStartEdit: (Long) -> Unit,
    onDismissEdit: () -> Unit,
    onEditCard: (Long, String, String) -> Unit,
    onRejectCard: (Long, String?) -> Unit,
) {
    val actionsEnabled = !uiState.isOffline && uiState.busyCardId == null
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.md),
        modifier = Modifier.fillMaxSize(),
    ) {
        uiState.visibleGroups.forEach { group ->
            item(key = "header-${group.bookId}-${group.chapter}-${group.cards.first().id}") {
                ChapterHeader(
                    bookId = group.bookId,
                    book = group.book,
                    chapter = group.chapter,
                    modifier =
                        Modifier.padding(
                            horizontal = RecallySpacing.screenPadding,
                            vertical = RecallySpacing.sm,
                        ),
                )
            }
            group.cards.forEach { card ->
                item(key = "card-${card.id}") {
                    ApproveCard(
                        card = card,
                        isEditing = uiState.editingCardId == card.id,
                        highlightsExpanded = uiState.isHighlightsExpanded(card.id),
                        actionsEnabled = actionsEnabled,
                        onToggleHighlights = { onToggleHighlights(card.id) },
                        onApprove = { onApproveCard(card.id) },
                        onStartEdit = { onStartEdit(card.id) },
                        onDismissEdit = onDismissEdit,
                        onSaveEdit = { front, back -> onEditCard(card.id, front, back) },
                        onReject = { reason -> onRejectCard(card.id, reason) },
                        modifier = Modifier.padding(horizontal = RecallySpacing.screenPadding),
                    )
                }
            }
        }
    }
}

/**
 * One pending card. Every card carries the Reject / Edit / Approve row — an
 * approval card without it was a bug caught in the design audit
 * (design-system.md, "API gaps this design depends on").
 */
@Composable
private fun ApproveCard(
    card: PendingCard,
    isEditing: Boolean,
    highlightsExpanded: Boolean,
    actionsEnabled: Boolean,
    onToggleHighlights: () -> Unit,
    onApprove: () -> Unit,
    onStartEdit: () -> Unit,
    onDismissEdit: () -> Unit,
    onSaveEdit: (String, String) -> Unit,
    onReject: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rejectDialogOpen by remember(card.id) { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .background(MaterialTheme.recallyColors.surface)
                .border(
                    BorderStroke(HAIRLINE, MaterialTheme.recallyColors.line),
                    RoundedCornerShape(RecallyRadius.md),
                ),
    ) {
        BadgeRow(card = card)
        Column(
            verticalArrangement = Arrangement.spacedBy(RecallySpacing.md),
            modifier = Modifier.padding(RecallySpacing.cardPadding),
        ) {
            if (card.isCloze) {
                ClozeText(text = card.front, revealed = true)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
                    Text(
                        text = card.front,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.recallyColors.ink,
                    )
                    Text(
                        text = card.back,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.recallyColors.inkMuted,
                    )
                }
            }
            card.statusReason?.let { CritiqueBlock(critique = it) }
            HighlightDisclosure(
                highlights = card.sourceHighlights,
                truncated = card.truncated,
                expanded = highlightsExpanded,
                onToggle = onToggleHighlights,
            )
        }

        if (isEditing) {
            InlineEditor(
                card = card,
                enabled = actionsEnabled,
                onSave = onSaveEdit,
                onCancel = onDismissEdit,
            )
        } else {
            ActionRow(
                actionsEnabled = actionsEnabled,
                onApprove = onApprove,
                onStartEdit = onStartEdit,
                onReject = { rejectDialogOpen = true },
            )
        }
    }

    if (rejectDialogOpen) {
        RejectDialog(
            onConfirm = { reason ->
                rejectDialogOpen = false
                onReject(reason)
            },
            onDismiss = { rejectDialogOpen = false },
        )
    }
}

@Composable
private fun BadgeRow(card: PendingCard) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(RecallySpacing.cardPadding, RecallySpacing.md),
    ) {
        if (card.isNeedsHuman) {
            Badge(text = "NEEDS YOU", textColor = MaterialTheme.recallyColors.danger, fill = MaterialTheme.recallyColors.dangerWash)
        }
        Badge(
            text = if (card.isCloze) "CLOZE" else "Q&A",
            textColor = MaterialTheme.recallyColors.inkSoft,
            fill = null,
        )
        if (!card.isNeedsHuman) {
            Badge(text = "READY", textColor = MaterialTheme.recallyColors.primary, fill = MaterialTheme.recallyColors.primaryWash)
        }
        if (card.truncated) {
            TruncatedChip()
        }
    }
}

@Composable
private fun Badge(
    text: String,
    textColor: Color,
    fill: Color?,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        modifier =
            Modifier
                .then(
                    if (fill != null) {
                        Modifier.background(fill, RoundedCornerShape(RecallyRadius.sm))
                    } else {
                        Modifier.border(
                            BorderStroke(HAIRLINE, MaterialTheme.recallyColors.line),
                            RoundedCornerShape(RecallyRadius.sm),
                        )
                    },
                ).padding(horizontal = RecallySpacing.sm, vertical = RecallySpacing.xs),
    )
}

@Composable
private fun ActionRow(
    actionsEnabled: Boolean,
    onApprove: () -> Unit,
    onStartEdit: () -> Unit,
    onReject: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.recallyColors.lineSoft)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        ActionCell(
            label = "Reject",
            icon = {
                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.recallyColors.inkFaint)
            },
            labelColor = MaterialTheme.recallyColors.inkSoft,
            enabled = actionsEnabled,
            onClick = onReject,
            modifier = Modifier.weight(1f),
        )
        VerticalDivider()
        ActionCell(
            label = "Edit",
            icon = {
                Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.recallyColors.inkStrong)
            },
            labelColor = MaterialTheme.recallyColors.inkStrong,
            enabled = actionsEnabled,
            onClick = onStartEdit,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(bottomEnd = RecallyRadius.md))
                    .background(
                        if (actionsEnabled) {
                            MaterialTheme.recallyColors.primary
                        } else {
                            MaterialTheme.recallyColors.neutralWash
                        },
                    ).clickable(enabled = actionsEnabled, onClick = onApprove)
                    .padding(RecallySpacing.md),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (actionsEnabled) MaterialTheme.recallyColors.ground else MaterialTheme.recallyColors.inkFaint,
            )
            Text(
                text = "Approve",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (actionsEnabled) MaterialTheme.recallyColors.ground else MaterialTheme.recallyColors.inkFaint,
            )
        }
    }
}

/** Hairline divider between the Reject and Edit cells. */
@Composable
private fun RowScope.VerticalDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(HAIRLINE)
                .background(MaterialTheme.recallyColors.lineSoft),
    )
}

@Composable
private fun ActionCell(
    label: String,
    icon: @Composable () -> Unit,
    labelColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(RecallySpacing.md),
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) labelColor else MaterialTheme.recallyColors.inkFaint,
        )
    }
}

/** Edit inline, then approve with the edits (docs/api-spec.md, `POST /cards/{id}/approve`). */
@Composable
private fun InlineEditor(
    card: PendingCard,
    enabled: Boolean,
    onSave: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var editedFront by remember(card.id) { mutableStateOf(card.front) }
    var editedBack by remember(card.id) { mutableStateOf(card.back) }

    Column(
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
        modifier = Modifier.padding(RecallySpacing.cardPadding),
    ) {
        OutlinedTextField(
            value = editedFront,
            onValueChange = { editedFront = it },
            label = { Text("Front") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editedBack,
            onValueChange = { editedBack = it },
            label = { Text("Back") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(
                onClick = { onSave(editedFront, editedBack) },
                enabled = enabled && editedFront.isNotBlank(),
            ) {
                Text("Save & approve", color = MaterialTheme.recallyColors.primary)
            }
        }
    }
}

/** Reject with an optional reason (docs/android.md, "Screens → 3"). */
@Composable
private fun RejectDialog(
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reject card") },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason (optional — feeds the Learner)") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.ifBlank { null }) }) {
                Text("Reject", color = MaterialTheme.recallyColors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private val HAIRLINE = 1.dp
private val SKELETON_HEADER_WIDTH = 180.dp
private val SKELETON_CARD_HEIGHT = 180.dp
private val QUEUE_CLEAR_ICON_HALO = 48.dp

// --- Previews (docs/android.md, "Every previewable composable has a preview") ---

private val sampleQaCard =
    PendingCard(
        id = 56,
        status = PendingCard.STATUS_PENDING_REVIEW,
        type = PendingCard.TYPE_QA,
        front = "Why evaluate traces rather than individual steps?",
        back = "An LLM pipeline's behavior only makes sense end-to-end.",
        statusReason = null,
        sourceHighlights =
            listOf(
                "Traces capture the full execution path of an agent run.",
                "Step-level scores miss compounding errors.",
                "End-to-end behaviour is the unit users experience.",
            ),
        truncated = false,
        bookId = 1,
        book = "Evals for AI Engineers",
        chapter = "3. Error Analysis",
    )

private val sampleNeedsHumanClozeCard =
    PendingCard(
        id = 55,
        status = PendingCard.STATUS_NEEDS_HUMAN,
        type = PendingCard.TYPE_CLOZE,
        front = "The {{c1::Gulf of Specification}} is the gap between intent and instructions.",
        back = "—",
        statusReason = "Potentially ambiguous term; Writer 3 rounds unresolved.",
        sourceHighlights =
            listOf("The Gulf of Specification is this gap between our intent and our instructions…"),
        truncated = false,
        bookId = 1,
        book = "Evals for AI Engineers",
        chapter = "1. Introduction",
    )

private val sampleLoadedState =
    ApproveUiState(
        groups =
            groupIntoChapters(
                listOf(
                    sampleNeedsHumanClozeCard,
                    sampleQaCard.copy(truncated = true),
                ),
            ),
        pendingCount = 2,
        isLoading = false,
    )

@Composable
private fun ApproveScreenPreviewHost(uiState: ApproveUiState) {
    RecallyTheme {
        ApproveScreen(
            uiState = uiState,
            onFilterChange = {},
            onToggleHighlights = {},
            onApproveCard = {},
            onStartEdit = {},
            onDismissEdit = {},
            onEditCard = { _, _, _ -> },
            onRejectCard = { _, _ -> },
            onOpenSettings = {},
            onNavigateBack = {},
            onRetry = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun ApproveScreenLoadedPreview() {
    ApproveScreenPreviewHost(uiState = sampleLoadedState)
}

@CombinedPreviews
@Composable
private fun ApproveScreenQueueClearPreview() {
    ApproveScreenPreviewHost(uiState = ApproveUiState(isLoading = false))
}

@CombinedPreviews
@Composable
private fun ApproveScreenNeedsHumanPreview() {
    ApproveScreenPreviewHost(
        uiState =
            ApproveUiState(
                groups = groupIntoChapters(listOf(sampleNeedsHumanClozeCard)),
                isLoading = false,
                filter = QueueFilter.NEEDS_YOU,
            ),
    )
}

@CombinedPreviews
@Composable
private fun ApproveScreenTruncatedPreview() {
    ApproveScreenPreviewHost(
        uiState =
            ApproveUiState(
                groups = groupIntoChapters(listOf(sampleQaCard.copy(truncated = true))),
                isLoading = false,
                expandedHighlightCardIds = setOf(sampleQaCard.id),
            ),
    )
}

@CombinedPreviews
@Composable
private fun ApproveScreenLoadingPreview() {
    ApproveScreenPreviewHost(uiState = ApproveUiState(isLoading = true))
}

@CombinedPreviews
@Composable
private fun ApproveScreenOfflinePreview() {
    ApproveScreenPreviewHost(
        uiState = sampleLoadedState.copy(isOffline = true),
    )
}
