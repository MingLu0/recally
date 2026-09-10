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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
 * `GET /decks`. Pure composable — UiState in, callbacks out
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
        )
    }
}
