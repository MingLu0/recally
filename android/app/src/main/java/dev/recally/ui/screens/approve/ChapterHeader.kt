package dev.recally.ui.screens.approve

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.bookCoverColor
import dev.recally.ui.theme.recallyColors

/**
 * Chapter group header (design-system.md, "Chapter group header"): a 15×20dp
 * book-colour spine chip, vertically centred against a two-line text column —
 * the book title on line 1, the chapter on line 2. Groups a run of cards; the
 * flat list from `GET /cards/pending` is grouped client-side by
 * [groupIntoChapters].
 *
 * Two lines, not one (issue #201, superseding #146): #146 put both strings on
 * one line sharing width via `weight`, which fixed a ragged three-line wrap
 * but meant a long title + long chapter pair (e.g. "Building Generative AI
 * Services with FastAPI" / "2. Getting Started with FastAPI") both hit the
 * ellipsis and neither was readable. Each string now gets the full row width
 * on its own line.
 */
@Composable
fun ChapterHeader(
    bookId: Long,
    book: String,
    chapter: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
        modifier = modifier,
    ) {
        // Book spines are the one asymmetric radius (design-system.md,
        // "Spacing, radius, elevation") so the bound edge reads as a spine.
        Box(
            modifier =
                Modifier
                    .testTag(SPINE_TEST_TAG)
                    .size(width = SPINE_WIDTH, height = SPINE_HEIGHT)
                    .clip(
                        RoundedCornerShape(
                            topStart = 3.dp,
                            topEnd = 6.dp,
                            bottomEnd = 6.dp,
                            bottomStart = 3.dp,
                        ),
                    ).background(bookCoverColor(bookId, isSystemInDarkTheme())),
        )
        Column {
            Text(
                text = book,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.recallyColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chapter.isNotEmpty()) {
                Text(
                    text = chapter,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.recallyColors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val SPINE_TEST_TAG = "chapter_header_spine"

private val SPINE_WIDTH = 15.dp
private val SPINE_HEIGHT = 20.dp

@CombinedPreviews
@Composable
private fun ChapterHeaderPreview() {
    RecallyTheme {
        ChapterHeader(
            bookId = 1,
            book = "Evals for AI Engineers",
            chapter = "1. Introduction",
        )
    }
}

/** The overflow case from issue #146: both strings past one line. */
@CombinedPreviews
@Composable
private fun ChapterHeaderOverflowPreview() {
    RecallyTheme {
        ChapterHeader(
            bookId = 2,
            book = "Building Generative AI Services with FastAPI",
            chapter = "2. Getting Started with FastAPI",
        )
    }
}
