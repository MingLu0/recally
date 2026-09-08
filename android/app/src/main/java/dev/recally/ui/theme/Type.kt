package dev.recally.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.recally.R

/**
 * Type scale from docs/design/design-system.md, "Type". Inter Tight is the
 * single family; the platform sans-serif is the declared fallback, so a font
 * failure degrades cleanly. Screens write `MaterialTheme.typography.*` (or
 * [RecallyTypeScale] for steps without an M3 name) — never a raw `sp`.
 *
 * Letter-spacing values in the doc are px on a 390×844 artboard; at the
 * reference density 1px ≈ 1dp ≈ 1sp, so they are carried as sp here.
 */
val InterTight =
    FontFamily(
        Font(R.font.inter_tight_regular, FontWeight.Normal),
        Font(R.font.inter_tight_medium, FontWeight.Medium),
        Font(R.font.inter_tight_semibold, FontWeight.SemiBold),
        Font(R.font.inter_tight_bold, FontWeight.Bold),
        Font(R.font.inter_tight_extrabold, FontWeight.ExtraBold),
    )

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = InterTight,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing.sp,
)

val RecallyTypography =
    Typography(
        // display — sheet titles ("Session complete")
        headlineMedium = style(27, 34, FontWeight.ExtraBold),
        // title — screen headings ("Stats", "Decks", "Approve")
        titleLarge = style(22, 28, FontWeight.ExtraBold, letterSpacing = -0.7),
        // section — in-screen section headings ("Coming due", "Lapse rate")
        titleSmall = style(15, 20, FontWeight.ExtraBold),
        // card-question — the review card's question, the app's most-read text
        headlineSmall = style(24, 32, FontWeight.ExtraBold, letterSpacing = -0.7),
        // metric — stats-strip numbers
        titleMedium = style(21, 26, FontWeight.ExtraBold, letterSpacing = -0.4),
        // body-lg — answer text, cloze sentence
        bodyLarge = style(17, 27, FontWeight.Normal),
        // body — button labels, list rows
        bodyMedium = style(15, 22, FontWeight.Medium),
        // body-sm — secondary copy, sheet subtitles
        bodySmall = style(14, 21, FontWeight.Normal),
        // label — metadata, book titles in strips, filter chips
        labelLarge = style(13, 18, FontWeight.SemiBold),
        // caption — metric labels under numbers, nav labels
        labelMedium = style(12, 16, FontWeight.Normal),
        // badge — uppercase badges
        labelSmall = style(11, 14, FontWeight.Bold, letterSpacing = 0.4),
    )

/** Type-scale steps the doc gives no M3 name to. */
object RecallyTypeScale {
    /** metric-sm — summary-sheet row counts. */
    val metricSmall: TextStyle = style(19, 24, FontWeight.ExtraBold)
}
