package dev.recally.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Book cover colours (docs/design/design-system.md, "Colour"): data, not
 * theme. A fixed ordered list keyed off `book_id` — never generated randomly,
 * or a book's colour would change between installs. Extend by appending new
 * pairs to [BookCoverColors]; never reorder, or existing books change colour.
 */
data class BookCoverColor(
    val light: Color,
    val dark: Color,
)

val BookCoverColors: List<BookCoverColor> =
    listOf(
        // forest
        BookCoverColor(light = Color(0xFF24403A), dark = Color(0xFF2F5349)),
        // plum — dark values lifted so spines stay visible
        BookCoverColor(light = Color(0xFF4A3D55), dark = Color(0xFF5D4D6B)),
    )

/**
 * Stable colour for a book. Pure function of `book_id`, so the same book
 * renders the same colour on every install and across app restarts.
 */
fun bookCoverColor(
    bookId: Long,
    darkTheme: Boolean,
): Color {
    val entry = BookCoverColors[(abs(bookId % BookCoverColors.size)).toInt()]
    return if (darkTheme) entry.dark else entry.light
}
