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
 * The deck list (docs/design/RcDecks.dc.html): one row per book from
 * `GET /decks`. Pure composable — UiState in, callbacks out
 * (docs/android.md, "Pure screen composables").
 *
 * Rows show only what the endpoint documents: title, `total`, `due`, and the
 * G2 `progress` bar. The artboard's TRUNCATED badge (G6) stays scoped out —
 * the endpoint documents no truncated count.
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
            "${uiState.totalCards} cards · ${uiState.decks.size} books",
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
                "${deck.total} cards",
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkFaint,
            )
            Spacer(Modifier.height(7.dp))
            DeckProgressBar(progress = deck.progress)
        }
        if (deck.due > 0) {
            Badge(text = "${deck.due} DUE", fill = colors.primaryWash, textColor = colors.primary)
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
                            Deck(bookId = 1, title = "Evals for AI Engineers", total = 48, due = 6, progress = 0.62f),
                            Deck(bookId = 2, title = "30 Agents in 30 Days", total = 83, due = 0, progress = 0.24f),
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
                    decks = listOf(Deck(bookId = 1, title = "Evals for AI Engineers", total = 48, due = 6, progress = 0.62f)),
                ),
            onDeckClick = {},
            onRetry = {},
            onOpenSettings = {},
        )
    }
}
