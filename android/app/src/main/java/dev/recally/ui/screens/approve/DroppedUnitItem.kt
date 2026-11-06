package dev.recally.ui.screens.approve

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.recally.domain.model.DroppedUnit
import dev.recally.domain.model.PendingCard
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * The Dropped list (issue #365; design-system.md, "Dropped item"): the
 * Curator's unresolved drops under the same chapter headers as the queue,
 * in the server's order. Pure: UiState in, callbacks out.
 */
@Composable
internal fun DroppedList(
    uiState: ApproveUiState,
    onDismissDrop: (Long) -> Unit,
    onGenerateDrop: (Long) -> Unit,
    onStartManualCard: (Long) -> Unit,
    onManualCardChange: (ManualCardDraft) -> Unit,
    onCancelManualCard: () -> Unit,
    onSubmitManualCard: () -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.md),
        modifier = Modifier.fillMaxSize().testTag(DROPPED_LIST_TAG),
    ) {
        uiState.droppedGroups.forEach { group ->
            item(key = "dropped-header-${group.bookId}-${group.chapter}-${group.units.first().id}") {
                ChapterHeader(
                    bookId = group.bookId,
                    book = group.book,
                    chapter = group.chapter,
                    modifier = Modifier.padding(horizontal = RecallySpacing.screenPadding, vertical = RecallySpacing.sm),
                )
            }
            group.units.forEach { unit ->
                item(key = "dropped-${unit.id}") {
                    DroppedUnitItem(
                        unit = unit,
                        actionsEnabled = uiState.dropActionsEnabled,
                        isGenerating = uiState.generatingUnitId == unit.id,
                        error = uiState.dropErrors[unit.id],
                        draft = uiState.manualCardDraft?.takeIf { it.unitId == unit.id },
                        onDismiss = { onDismissDrop(unit.id) },
                        onGenerate = { onGenerateDrop(unit.id) },
                        onStartManualCard = { onStartManualCard(unit.id) },
                        onManualCardChange = onManualCardChange,
                        onCancelManualCard = onCancelManualCard,
                        onSubmitManualCard = onSubmitManualCard,
                        modifier = Modifier.padding(horizontal = RecallySpacing.screenPadding),
                    )
                }
            }
        }
    }
}

/**
 * One dropped unit on the card surface. Its source highlights are the
 * content here, not context, so they are always visible — never behind the
 * collapsed disclosure the queue uses — numbered "Source N" when there are
 * several (issue #268). The Curator's reason sits in the critique block
 * under "WHY IT WAS DROPPED". Truncation is flagged, never repaired (hard
 * rule 7).
 *
 * The action row is Dismiss · Write card · Generate. Dismiss and Generate
 * each open a confirm dialog that explains the consequence (issue #288's
 * pattern); Write card opens the inline editor. While Generate runs (often
 * 30–90 s) a progress row replaces the actions.
 */
@Composable
private fun DroppedUnitItem(
    unit: DroppedUnit,
    actionsEnabled: Boolean,
    isGenerating: Boolean,
    error: String?,
    draft: ManualCardDraft?,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit,
    onStartManualCard: () -> Unit,
    onManualCardChange: (ManualCardDraft) -> Unit,
    onCancelManualCard: () -> Unit,
    onSubmitManualCard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dismissDialogOpen by remember(unit.id) { mutableStateOf(false) }
    var generateDialogOpen by remember(unit.id) { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .background(MaterialTheme.recallyColors.surface)
                .border(BorderStroke(HAIRLINE, MaterialTheme.recallyColors.line), RoundedCornerShape(RecallyRadius.md)),
    ) {
        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
                    modifier = Modifier.fillMaxWidth().padding(RecallySpacing.cardPadding, RecallySpacing.md),
                ) {
                    Badge(text = "DROPPED", textColor = MaterialTheme.recallyColors.inkSoft, fill = null)
                    if (unit.truncated) {
                        TruncatedChip()
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(RecallySpacing.md),
                    modifier =
                        Modifier.padding(
                            start = RecallySpacing.cardPadding,
                            end = RecallySpacing.cardPadding,
                            bottom = RecallySpacing.cardPadding,
                        ),
                ) {
                    SourceHighlights(highlights = unit.sourceHighlights)
                    unit.reason?.let { CritiqueBlock(critique = it, label = "WHY IT WAS DROPPED") }
                    error?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.recallyColors.danger)
                    }
                }
            }
        }

        when {
            draft != null ->
                ManualCardEditor(
                    draft = draft,
                    enabled = actionsEnabled,
                    onChange = onManualCardChange,
                    onCancel = onCancelManualCard,
                    onSubmit = onSubmitManualCard,
                )
            isGenerating -> GeneratingRow()
            else ->
                DroppedActionRow(
                    actionsEnabled = actionsEnabled,
                    onDismiss = { dismissDialogOpen = true },
                    onWriteCard = onStartManualCard,
                    onGenerate = { generateDialogOpen = true },
                )
        }
    }

    if (dismissDialogOpen) {
        DismissDropDialog(
            confirmEnabled = actionsEnabled,
            onConfirm = {
                dismissDialogOpen = false
                onDismiss()
            },
            onDismiss = { dismissDialogOpen = false },
        )
    }
    if (generateDialogOpen) {
        GenerateDropDialog(
            confirmEnabled = actionsEnabled,
            onConfirm = {
                generateDialogOpen = false
                onGenerate()
            },
            onDismiss = { generateDialogOpen = false },
        )
    }
}

/** Every source in body text; "Source N" headings only when there is more than one (issue #268). */
@Composable
private fun SourceHighlights(highlights: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
        highlights.forEachIndexed { index, highlight ->
            Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs)) {
                if (highlights.size > 1) {
                    Text(
                        text = "Source ${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.recallyColors.inkFaint,
                    )
                }
                Text(text = highlight, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.recallyColors.ink)
            }
        }
    }
}

@Composable
private fun DroppedActionRow(
    actionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onWriteCard: () -> Unit,
    onGenerate: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.recallyColors.lineSoft)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        ActionCell(
            label = "Dismiss",
            icon = { Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.recallyColors.inkFaint) },
            labelColor = MaterialTheme.recallyColors.inkSoft,
            enabled = actionsEnabled,
            onClick = onDismiss,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(bottomStart = RecallyRadius.md)),
        )
        VerticalDivider()
        ActionCell(
            label = "Write card",
            icon = {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = if (actionsEnabled) MaterialTheme.recallyColors.inkStrong else MaterialTheme.recallyColors.inkFaint,
                )
            },
            labelColor = MaterialTheme.recallyColors.inkStrong,
            enabled = actionsEnabled,
            onClick = onWriteCard,
            modifier = Modifier.weight(1f),
        )
        VerticalDivider()
        ActionCell(
            label = "Generate",
            icon = {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (actionsEnabled) MaterialTheme.recallyColors.primary else MaterialTheme.recallyColors.inkFaint,
                )
            },
            labelColor = MaterialTheme.recallyColors.primary,
            enabled = actionsEnabled,
            onClick = onGenerate,
            modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(bottomEnd = RecallyRadius.md)),
        )
    }
}

/** Generate's in-flight state: the call answers only after the cards are written. */
@Composable
private fun GeneratingRow() {
    HorizontalDivider(color = MaterialTheme.recallyColors.lineSoft)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
        modifier = Modifier.fillMaxWidth().padding(RecallySpacing.md).testTag(DROPPED_GENERATING_TAG),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.recallyColors.inkFaint)
        Text(
            text = "Writing cards — this can take a minute…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.recallyColors.inkMuted,
        )
    }
}

/**
 * "Write card": a Q&A | Cloze toggle, Front and Back (the queue's inline
 * editor shape), and Add to queue. Add stays disabled until the card is
 * plausible ([ManualCardDraft.canSubmit]); the server validates. The editor
 * never approves — the card still goes through Approve (hard rule 1).
 */
@Composable
private fun ManualCardEditor(
    draft: ManualCardDraft,
    enabled: Boolean,
    onChange: (ManualCardDraft) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.recallyColors.lineSoft)
    Column(
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
        modifier = Modifier.padding(RecallySpacing.cardPadding),
    ) {
        val types = listOf(PendingCard.TYPE_QA to "Q&A", PendingCard.TYPE_CLOZE to "Cloze")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            types.forEachIndexed { index, (type, label) ->
                SegmentedButton(
                    selected = draft.type == type,
                    onClick = { onChange(draft.copy(type = type)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                    // Tokens, not Material's default palette (emulator verification
                    // caught the lavender selected fill): selected reads like the
                    // `primary-wash` badges, unselected is the item's own surface.
                    colors =
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.recallyColors.primaryWash,
                            activeContentColor = MaterialTheme.recallyColors.primary,
                            activeBorderColor = MaterialTheme.recallyColors.line,
                            inactiveContainerColor = MaterialTheme.recallyColors.surface,
                            inactiveContentColor = MaterialTheme.recallyColors.inkMuted,
                            inactiveBorderColor = MaterialTheme.recallyColors.line,
                        ),
                    label = { Text(label) },
                )
            }
        }
        OutlinedTextField(
            value = draft.front,
            onValueChange = { onChange(draft.copy(front = it)) },
            label = { Text("Front") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.showsClozeHint) {
            Text(
                text = "Wrap the answer in {{c1::…}} — e.g. The {{c1::Gulf of Specification}} is the gap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.recallyColors.inkFaint,
            )
        }
        OutlinedTextField(
            value = draft.back,
            onValueChange = { onChange(draft.copy(back = it)) },
            label = { Text(if (draft.type == PendingCard.TYPE_CLOZE) "Back (optional)" else "Back") },
            modifier = Modifier.fillMaxWidth(),
        )
        draft.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.recallyColors.danger)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = onSubmit, enabled = enabled && draft.canSubmit) {
                Text(
                    text = "Add to queue",
                    color = if (enabled && draft.canSubmit) MaterialTheme.recallyColors.primary else MaterialTheme.recallyColors.inkFaint,
                )
            }
        }
    }
}

/** Dismiss's confirm: the Curator was right. There is no undo (issue #365, scoped out). */
@Composable
private fun DismissDropDialog(
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dismiss this highlight?") },
        text = { Text("The Curator was right — hide this highlight. It leaves Dropped and makes no card; there is no undo.") },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text("Dismiss", color = MaterialTheme.recallyColors.inkStrong)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = Modifier.testTag(DISMISS_DROP_DIALOG_TAG),
    )
}

/** Generate's confirm names the loop it runs and where the card lands. */
@Composable
private fun GenerateDropDialog(
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate cards?") },
        text = {
            Text(
                "Runs the Writer ⇄ Critic loop on this highlight. The cards land in the queue for your " +
                    "approval — nothing is scheduled until you approve. This can take a minute.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text("Generate", color = MaterialTheme.recallyColors.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = Modifier.testTag(GENERATE_DROP_DIALOG_TAG),
    )
}

/** Dropped drained: its own centred state, distinct from "Queue clear" (design-system.md, "States"). */
@Composable
internal fun DroppedEmpty() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.md, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().padding(RecallySpacing.screenPadding),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).background(MaterialTheme.recallyColors.neutralWash, CircleShape),
        ) {
            Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = MaterialTheme.recallyColors.inkFaint)
        }
        Text(text = "No dropped highlights", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.recallyColors.ink)
        Text(
            text = "Everything the Curator dropped has been dismissed or turned into a card.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.recallyColors.inkFaint,
            textAlign = TextAlign.Center,
        )
    }
}

internal const val DROPPED_GENERATING_TAG = "droppedGenerating"

// --- Previews ---------------------------------------------------------------

private val sampleDroppedGroup =
    DroppedUnit(
        id = 12,
        reason = "Sibling headings with no content of their own; nothing to recall.",
        curatedText = "Error analysis. Axial coding.",
        sourceHighlights = listOf("Error analysis", "Axial coding and the trace review"),
        truncated = true,
        bookId = 1,
        book = "Evals for AI Engineers",
        chapter = "3. Error Analysis",
    )

private val sampleDroppedSingle =
    sampleDroppedGroup.copy(
        id = 13,
        reason = "Narrative framing with no retrievable claim.",
        sourceHighlights = listOf("In this chapter we walk through a worked example end to end."),
        truncated = false,
    )

private fun sampleDroppedState(
    manualCardDraft: ManualCardDraft? = null,
    generatingUnitId: Long? = null,
    isOffline: Boolean = false,
) = ApproveUiState(
    droppedGroups = groupDropsIntoChapters(listOf(sampleDroppedGroup, sampleDroppedSingle)),
    droppedCount = 2,
    filter = QueueFilter.DROPPED,
    isLoading = false,
    isOffline = isOffline,
    manualCardDraft = manualCardDraft,
    generatingUnitId = generatingUnitId,
    busyDropUnitId = generatingUnitId,
)

@Composable
private fun DroppedListPreviewHost(uiState: ApproveUiState) {
    RecallyTheme {
        DroppedList(
            uiState = uiState,
            onDismissDrop = {},
            onGenerateDrop = {},
            onStartManualCard = {},
            onManualCardChange = {},
            onCancelManualCard = {},
            onSubmitManualCard = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun DroppedListPreview() {
    DroppedListPreviewHost(sampleDroppedState())
}

@CombinedPreviews
@Composable
private fun DroppedListGeneratingPreview() {
    DroppedListPreviewHost(sampleDroppedState(generatingUnitId = sampleDroppedSingle.id))
}

@CombinedPreviews
@Composable
private fun DroppedListManualEditorPreview() {
    DroppedListPreviewHost(
        sampleDroppedState(
            manualCardDraft =
                ManualCardDraft(
                    unitId = sampleDroppedSingle.id,
                    type = PendingCard.TYPE_CLOZE,
                    front = "A worked {{c1::example}} runs end to end.",
                ),
        ),
    )
}

@CombinedPreviews
@Composable
private fun DroppedListOfflinePreview() {
    DroppedListPreviewHost(sampleDroppedState(isOffline = true))
}

@CombinedPreviews
@Composable
private fun DroppedEmptyPreview() {
    RecallyTheme { DroppedEmpty() }
}

@CombinedPreviews
@Composable
private fun DismissDropDialogPreview() {
    RecallyTheme { DismissDropDialog(confirmEnabled = true, onConfirm = {}, onDismiss = {}) }
}

@CombinedPreviews
@Composable
private fun GenerateDropDialogPreview() {
    RecallyTheme { GenerateDropDialog(confirmEnabled = true, onConfirm = {}, onDismiss = {}) }
}
