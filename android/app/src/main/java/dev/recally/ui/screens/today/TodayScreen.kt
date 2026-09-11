package dev.recally.ui.screens.today

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.recally.domain.model.Deck
import dev.recally.ui.screens.approve.QueueFilter
import dev.recally.ui.screens.decks.BookSpineChip
import dev.recally.ui.screens.decks.DeckProgressBar
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
 * The "N to approve" / "N need you" tiles read the collection-wide `counts`
 * of `GET /cards/pending` (G1, issue #132). The "Your books" rail below them
 * renders `GET /decks` (G2, issue #133; rail built in #154) with the same
 * spine chip and progress bar Decks uses. Today works having never received
 * a push: everything else it renders comes from `GET /reviews/due` and
 * `GET /stats` (docs/android.md, "Push notifications").
 */
@Composable
fun TodayScreen(
    uiState: TodayUiState,
    onStartReview: () -> Unit,
    onOpenApprove: (QueueFilter) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onBookClick: (Long) -> Unit,
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
                    // The book rail can push the lower content past a small
                    // display; Today scrolls rather than clipping it.
                    .verticalScroll(rememberScrollState())
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
                        nextDueLabel = uiState.nextDueLabel,
                    )
            }
            WaitingForYouSection(
                pendingReviewCount = uiState.pendingReviewCount,
                needsHumanCount = uiState.needsHumanCount,
                onOpenApprove = onOpenApprove,
            )
            if (uiState.books.isNotEmpty() || uiState.booksFailedToLoad) {
                YourBooksRail(
                    books = uiState.books,
                    failedToLoad = uiState.booksFailedToLoad,
                    onBookClick = onBookClick,
                    onRetry = onRetry,
                )
            }
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
                imageVector = wordmarkBookVector(colors.ground),
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
 * The "Waiting for you" section (artboard `RcWhite.dc.html`): a section title
 * over a two-column grid of queue-bucket tiles. Approve is entered from here
 * rather than from the bottom bar (docs/android.md, "Navigation") — it is a
 * modal task you finish and leave.
 *
 * The tiles carry the collection-wide `counts` of `GET /cards/pending` (G1,
 * issue #132) — never a list length, since the queue requires connectivity and
 * Today must render before it is reachable. While the counts are unknown the
 * whole section is absent: a heading with nothing under it reads as an error.
 *
 * The "need you" tile is dropped at zero — `needs_human` is an exception
 * state, and a permanent "0 need you" tile makes the common case look like it
 * has an outstanding problem.
 *
 * Each tile opens Approve on the filter it counts (issue #178). A tile whose
 * count is meaningful but whose destination is not lands the user on a list
 * where the cards they asked for are mixed in with everything else.
 */
@Composable
private fun WaitingForYouSection(
    pendingReviewCount: Int?,
    needsHumanCount: Int?,
    onOpenApprove: (QueueFilter) -> Unit,
) {
    if (pendingReviewCount == null) return
    val colors = MaterialTheme.recallyColors
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
        Text(
            text = "Waiting for you",
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
            QueueCountTile(
                count = pendingReviewCount,
                label = "to approve",
                accent = colors.primary,
                onClick = { onOpenApprove(QueueFilter.ALL) },
                modifier = Modifier.weight(1f),
            )
            if (needsHumanCount != null && needsHumanCount > 0) {
                QueueCountTile(
                    count = needsHumanCount,
                    label = "need you",
                    accent = colors.danger,
                    onClick = { onOpenApprove(QueueFilter.NEEDS_YOU) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One queue-bucket tile: a colour-washed dot, then the metric stacked over its
 * label. The number is `ink` and the label `ink-soft` — colour lands on the
 * mark, not the figure, because both tiles are counts of the same kind and a
 * coloured number would read as a status (design-system.md, "Rules").
 */
@Composable
private fun QueueCountTile(
    count: Int,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.md),
        modifier =
            modifier
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                .clickable(onClick = onClick)
                .padding(RecallySpacing.cardPadding),
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .background(accent, RoundedCornerShape(RecallyRadius.pill)),
        )
        Column {
            Text(
                text = "$count",
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Normal,
                color = colors.inkSoft,
            )
        }
    }
}

/**
 * The "Your books" rail (artboard `RcWhite.dc.html`; G2, issues #133/#154):
 * section title, subtitle, and a horizontally scrolling row with one card per
 * book — spine chip, title, card count and the server's `progress`. The row
 * components are the ones Decks uses (`BookSpineChip`, `DeckProgressBar`), so
 * a book reads identically on both screens.
 *
 * A **loaded and genuinely empty** library draws nothing — the caller keeps
 * the whole section out rather than putting a header over an empty rail. A
 * rail whose load **failed** is a different thing and says so, via
 * [failedToLoad] (issue #189): previously both cases were silence, so a
 * `GET /decks` that never answered was indistinguishable from a user with no
 * books. The failure strip stays inside the section — the rest of Today is
 * untouched, decks being remote-only.
 *
 * The artboard's "Import" tile is deliberately not built: ingestion is the
 * watched folder on the Mac (docs/android.md) and `api-spec.md` documents no
 * client-initiated import — the app does not invent one (issue #154).
 */
@Composable
private fun YourBooksRail(
    books: List<Deck>,
    failedToLoad: Boolean,
    onBookClick: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs)) {
        Text(
            text = "Your books",
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
        )
        Text(
            text = "From your O'Reilly highlights",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(RecallySpacing.sm))
        if (books.isEmpty() && failedToLoad) {
            BooksFailureStrip(onRetry = onRetry)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
                items(books, key = { it.bookId }) { deck ->
                    BookRailCard(deck = deck, onClick = { onBookClick(deck.bookId) })
                }
            }
        }
    }
}

/**
 * The rail's own failure state (design-system.md, "Your books rail"): a muted
 * line inside the section with a quiet retry, never a screen-level error
 * banner. `onRetry` is Today's existing refresh — the rail has no fetch of
 * its own.
 */
@Composable
private fun BooksFailureStrip(onRetry: () -> Unit) {
    val colors = MaterialTheme.recallyColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.md),
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                .padding(RecallySpacing.cardPadding),
    ) {
        Text(
            text = "Couldn't load your books",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}

/**
 * One book on the rail: spine chip and title, then count and progress. The
 * whole card is the click target and opens Book detail — the same destination
 * the Decks row opens (issue #180). The section heading is deliberately left
 * outside it: chrome is not a book.
 */
@Composable
private fun BookRailCard(
    deck: Deck,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
        modifier =
            Modifier
                .width(190.dp)
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                .clickable(onClick = onClick)
                .padding(RecallySpacing.cardPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
        ) {
            BookSpineChip(bookId = deck.bookId, title = deck.title)
            Text(
                text = deck.title,
                style = MaterialTheme.typography.labelLarge,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "${deck.total} card${if (deck.total == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkFaint,
        )
        DeckProgressBar(progress = deck.progress)
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

/**
 * The book glyph inside the wordmark square, stroked with [strokeColor] — the
 * theme's `ground` token, so it inverts with the `ink`-filled tile behind it
 * (design-system.md: tile = `ink`, glyph = `ground` in both themes).
 */
@Composable
private fun wordmarkBookVector(strokeColor: Color): ImageVector =
    remember(strokeColor) {
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

/** Exposes [wordmarkBookVector] to WordmarkTest, reading the theme's `ground` token as the call site does. */
@Composable
internal fun wordmarkBookVectorForTest(): ImageVector = wordmarkBookVector(MaterialTheme.recallyColors.ground)

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
            onBookClick = {},
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
                    pendingReviewCount = 8,
                    needsHumanCount = 3,
                    books =
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
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
            onBookClick = {},
        )
    }
}

/** A loaded, genuinely empty library: no rail, the rest of Today renders. */
@CombinedPreviews
@Composable
private fun TodayScreenEmptyBooksPreview() {
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
                    books = emptyList(),
                ),
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
            onBookClick = {},
        )
    }
}

/**
 * `GET /decks` did not answer (issue #189): the section renders its failure
 * strip, so a rail that could not load never reads as a library with no books.
 */
@CombinedPreviews
@Composable
private fun TodayScreenBooksFailedPreview() {
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
                    books = emptyList(),
                    booksFailedToLoad = true,
                ),
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
            onBookClick = {},
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
                    nextDueLabel = "next card in 4 hours",
                ),
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
            onBookClick = {},
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
                    // Decks are remote-only; the rail keeps the last-known
                    // books when a refresh fails.
                    books =
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
            onStartReview = {},
            onOpenApprove = {},
            onOpenSettings = {},
            onRetry = {},
            onBookClick = {},
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
            onBookClick = {},
        )
    }
}
