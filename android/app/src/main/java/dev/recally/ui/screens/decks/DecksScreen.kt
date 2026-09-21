package dev.recally.ui.screens.decks

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.recally.domain.model.Deck
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * "48 cards · 9 chapters" (docs/design/RcDecks.dc.html). `chapters` is a
 * `GET /decks` field (issue #172) — this screen never fetches a book's cards,
 * so it cannot count them itself. A book with no chapters yet shows the card
 * count alone rather than a bare "0 chapters".
 */
private fun deckSubtitle(deck: Deck): String {
    val cardCount = "${deck.total} card${if (deck.total == 1) "" else "s"}"
    if (deck.chapters == 0) return cardCount
    return "$cardCount · ${deck.chapters} chapter${if (deck.chapters == 1) "" else "s"}"
}

/**
 * The deck list (docs/design/RcDecks.dc.html): one row per book from
 * `GET /decks`, with the import tile at the foot of the list (issue #234).
 * Pure composable — UiState in, callbacks out
 * (docs/android.md, "Pure screen composables").
 *
 * Rows show only what the endpoint documents: title, `total`, `chapters`,
 * `due`, `truncated`, and the `progress` bar — every one a `GET /decks` field.
 * The artboard's TRUNCATED badge is the last of those (G6, issue #173): a
 * flag on how many of the book's source highlights the O'Reilly export
 * clipped. It never offers to recover the text (hard rule 7).
 */

@Composable
fun DecksScreen(
    uiState: DecksUiState,
    onDeckClick: (Long) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onExportPicked: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    // MIME `text/*`, not `text/csv`: some providers report `text/plain` for a
    // .csv, and a narrower filter would make the export unpickable
    // (docs/android.md, *Screens → 4. Decks*). The app is a transport: the
    // Uri goes to the ViewModel, the bytes go to the server, nothing parses
    // here.
    val exportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onExportPicked(uri)
        }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.ground)
                .padding(horizontal = RecallySpacing.screenPadding),
    ) {
        Spacer(Modifier.height(RecallySpacing.screenPadding))
        Text("Decks", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        Text(
            "${uiState.totalCards} card${if (uiState.totalCards == 1) "" else "s"} · " +
                "${uiState.decks.size} book${if (uiState.decks.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(RecallySpacing.md))

        if (uiState.isOffline) OfflineBar()
        if (uiState.isUnauthorized) UnauthorizedBanner(onOpenSettings)
        uiState.errorMessage?.let { ErrorRow(message = it, onRetry = onRetry) }

        if (uiState.isLoading && uiState.decks.isEmpty()) {
            // Skeleton blocks at the real row's dimensions — no spinners
            // (design-system.md, "States").
            Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
                repeat(3) { SkeletonBlock(height = 72.dp) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
                items(uiState.decks, key = { it.bookId }) { deck ->
                    DeckRow(deck = deck, onClick = { onDeckClick(deck.bookId) })
                }
                item(key = "import-tile") {
                    ImportTile(
                        state = uiState.importState,
                        onClick = { exportPicker.launch(arrayOf("text/*")) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckRow(
    deck: Deck,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                .clickable(onClick = onClick)
                .padding(RecallySpacing.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Book cover colours are data keyed off book_id, not theme
        // (design-system.md, "Colour").
        BookSpineChip(bookId = deck.bookId, title = deck.title)
        Spacer(Modifier.width(RecallySpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                deck.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Text(
                deckSubtitle(deck),
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkFaint,
            )
            Spacer(Modifier.height(7.dp))
            DeckProgressBar(progress = deck.progress)
        }
        // Both badges in one 6dp-gapped column, as the artboard stacks them
        // (docs/design/RcDecks.dc.html). Each hides at zero rather than
        // rendering "0 DUE" / "0 TRUNCATED".
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (deck.due > 0) {
                Badge(
                    text = "${deck.due} DUE",
                    fill = colors.primaryWash,
                    textColor = colors.primary,
                )
            }
            if (deck.truncated > 0) {
                // `warn` wash, matching the per-card TRUNCATED SOURCE chip on
                // Approve (design-system.md, "States"). Flag only.
                Badge(
                    text = "${deck.truncated} TRUNCATED",
                    fill = colors.warnWash,
                    textColor = colors.warn,
                )
            }
        }
    }
}

/**
 * The dashed import affordance at the foot of the deck list
 * (docs/design/RcDecks.dc.html, issue #234). Idle offers the system picker;
 * uploading shows an indeterminate state for the whole server-side wait — the
 * pipeline reports no progress, so a percentage would sit at a lie; a result
 * replaces the label. Tapping a finished tile (success or failure) offers the
 * picker again. The rest of the list stays live throughout.
 */
@Composable
private fun ImportTile(
    state: ImportState,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    val enabled = state != ImportState.Uploading
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // Clip before the border and the ripple: the clip is what
                // keeps the click shadow inside the rounded corners, and the
                // dashed stroke is drawn inset so the full 1.5dp survives it.
                .clip(RoundedCornerShape(RecallyRadius.md))
                .dashedBorder(1.5.dp, colors.line, RecallyRadius.md)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = RecallySpacing.cardPadding, vertical = RecallySpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colors.neutralWash),
            contentAlignment = Alignment.Center,
        ) {
            if (state == ImportState.Uploading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.inkFaint)
            } else {
                Icon(
                    imageVector = ImportDownloadIcon,
                    contentDescription = null,
                    tint = colors.inkFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(RecallySpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                importHeadline(state),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkMuted,
            )
            Text(importSubline(state), style = MaterialTheme.typography.labelMedium, color = colors.inkFaint)
        }
    }
}

/** Line one: the offer, the wait, or the outcome in the tile's own words. */
private fun importHeadline(state: ImportState): String =
    when (state) {
        ImportState.Idle -> "Drop an O'Reilly export"
        ImportState.Uploading -> "Importing your export…"
        is ImportState.Success -> "Import complete"
        ImportState.Unreachable -> "Couldn't reach your backend"
        ImportState.Unauthorized -> "Check your API key in Settings"
        is ImportState.InvalidExport -> "Not a valid O'Reilly export"
        is ImportState.Unexpected -> "Import failed"
    }

/**
 * Line two: peers for the watcher, the honest wait, the run's counts, or the
 * failure's fix — three distinct failure messages mirroring the
 * connection-test convention (docs/android.md, "Connecting to the backend").
 */
private fun importSubline(state: ImportState): String =
    when (state) {
        ImportState.Idle -> "Or use the watched folder on your Mac"
        ImportState.Uploading -> "Cards appear in the approval queue when it finishes"
        is ImportState.Success ->
            "${state.rowsNew} new · ${state.rowsUpdated} updated · ${state.rowsRemoved} removed"
        ImportState.Unreachable -> "It may be off or unreachable from this network"
        ImportState.Unauthorized -> "The backend refused the key"
        is ImportState.InvalidExport -> state.detail ?: "Pick the CSV your O'Reilly export downloaded as"
        is ImportState.Unexpected -> state.detail ?: "Something went wrong on the server"
    }

/**
 * The artboard's dashed border (docs/design/RcDecks.dc.html): 1.5dp `line`
 * dashes. The stroke is drawn inset by half its width so it lands whole
 * inside a caller-applied `clip` — the same convention as the platform
 * `Modifier.border` on the deck rows.
 */
private fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    radius: Dp,
): Modifier =
    drawBehind {
        val strokeWidth = width.toPx()
        val inset = strokeWidth / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius((radius - width / 2).toPx()),
            style =
                Stroke(
                    width = strokeWidth,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                ),
        )
    }

/**
 * Lucide `download`, verbatim from the import affordance in
 * RcDecks.dc.html / DkDecks.dc.html (24×24, stroke 2, round caps). Private to
 * this package like QueueTileIcons for Today — the core material-icons set
 * this app builds on does not carry it.
 */
private fun ImageVector.Builder.strokePath(svgPath: String) =
    addPath(
        pathData = addPathNodes(svgPath),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )

private val ImportDownloadIcon: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "import_download",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).strokePath("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4")
        .strokePath("M7 10l5 5 5-5")
        .strokePath("M12 15V3")
        .build()
}

@CombinedPreviews
@Composable
private fun DecksScreenPreview() {
    RecallyTheme {
        DecksScreen(
            uiState =
                DecksUiState(
                    decks =
                        listOf(
                            Deck(
                                bookId = 1,
                                title = "Evals for AI Engineers",
                                total = 48,
                                due = 6,
                                progress = 0.62f,
                                chapters = 9,
                                truncated = 0,
                            ),
                            Deck(
                                bookId = 2,
                                title = "30 Agents in 30 Days",
                                total = 83,
                                due = 0,
                                progress = 0.24f,
                                chapters = 9,
                                truncated = 2,
                            ),
                        ),
                ),
            onDeckClick = {},
            onRetry = {},
            onOpenSettings = {},
            onExportPicked = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun DecksScreenLoadingPreview() {
    RecallyTheme {
        DecksScreen(
            uiState = DecksUiState(isLoading = true),
            onDeckClick = {},
            onRetry = {},
            onOpenSettings = {},
            onExportPicked = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun DecksScreenOfflinePreview() {
    RecallyTheme {
        DecksScreen(
            uiState =
                DecksUiState(
                    isOffline = true,
                    decks =
                        listOf(
                            Deck(
                                bookId = 1,
                                title = "Evals for AI Engineers",
                                total = 48,
                                due = 6,
                                progress = 0.62f,
                                chapters = 9,
                                truncated = 0,
                            ),
                        ),
                ),
            onDeckClick = {},
            onRetry = {},
            onOpenSettings = {},
            onExportPicked = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun ImportTileUploadingPreview() {
    RecallyTheme {
        ImportTile(state = ImportState.Uploading, onClick = {})
    }
}

@CombinedPreviews
@Composable
private fun ImportTileResultPreview() {
    RecallyTheme {
        Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
            ImportTile(state = ImportState.Success(rowsNew = 56, rowsUpdated = 0, rowsRemoved = 2), onClick = {})
            ImportTile(state = ImportState.Unreachable, onClick = {})
            ImportTile(state = ImportState.InvalidExport(detail = null), onClick = {})
        }
    }
}
