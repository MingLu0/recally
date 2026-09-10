package dev.recally.ui.screens.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.bookCoverColor
import dev.recally.ui.theme.recallyColors
import kotlin.math.roundToInt

// The book visuals both deck screens share (docs/design/RcDecks.dc.html and
// RcBook.dc.html). The book-detail header renders the same spine, progress
// and due count as the book's Decks row (issue #152), so both screens draw
// them from here — the header can never drift from the row again.

/**
 * The spine chip: a 44dp cover in the book's stable colour
 * (`bookCoverColor(bookId)` — data keyed off `book_id`, never theme;
 * design-system.md, "Colour") with the title's initial centred.
 */
@Composable
internal fun BookSpineChip(
    bookId: Long,
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clip(RoundedCornerShape(RecallyRadius.sm))
                .background(bookCoverColor(bookId, isSystemInDarkTheme())),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

/**
 * The progress bar: a 5dp `pill` track with a proportional fill, plus the
 * percentage in `labelMedium`/700. The artboard draws high progress in
 * `success` and low in `accent`; half learned is where the fill flips.
 */
@Composable
internal fun DeckProgressBar(progress: Float) {
    val colors = MaterialTheme.recallyColors
    val fillColor = if (progress >= 0.5f) colors.success else colors.accent
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(RecallyRadius.pill))
                    .background(colors.track),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(5.dp)
                        .background(fillColor, RoundedCornerShape(RecallyRadius.pill)),
            )
        }
        Spacer(Modifier.width(RecallySpacing.sm))
        Text(
            "${(progress * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = fillColor,
        )
    }
}
