package dev.recally.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens from docs/design/design-system.md, "Colour". Both themes are
 * specified; dark values are re-derived, not inverted. The token set is wider
 * than the M3 ColorScheme roles — tokens without an M3 role ride along on
 * [RecallyColorTokens] and are exposed through [androidx.compose.material3.MaterialTheme.recallyColors].
 *
 * The table in the doc is authoritative; if a value here drifts from it, the
 * doc wins and this file is the bug.
 */
data class RecallyColorTokens(
    /** Page background. M3 `surface`. */
    val ground: Color,
    /** Card fill. Dark only — light cards are unfilled, so light maps this to [ground]. M3 `surfaceContainer`. */
    val surface: Color,
    /** Headings, card question text, primary labels. M3 `onSurface`. */
    val ink: Color,
    /** Secondary interactive labels — outlined button text, collapsed chapter rows. */
    val inkStrong: Color,
    /** Body copy, answer text, secondary labels. M3 `onSurfaceVariant`. */
    val inkMuted: Color,
    /** Tertiary labels: list-row values, sub-labels beside a metric. */
    val inkSoft: Color,
    /** Metadata, chapter names, placeholder text. M3 `outline`. */
    val inkFaint: Color,
    /** Card and control borders. M3 `outlineVariant`. */
    val line: Color,
    /** Dividers inside a card. Collapses onto [line] in dark. */
    val lineSoft: Color,
    /** Unfilled portion of a progress bar. Fill only, never a border. */
    val track: Color,
    /** Neutral chip fills, status-free icon backgrounds. M3 `surfaceVariant`. */
    val neutralWash: Color,
    /** Non-focal data marks — forecast chart's non-today bars, unrevealed cloze rule. Never text. */
    val primaryMuted: Color,
    /** Primary action fill, active nav, cloze blank, selected filter. M3 `primary`. */
    val primary: Color,
    /** Pressed state. Dark presses by opacity, not hue. */
    val primaryDark: Color,
    /** Cloze blank background, primary-tinted surfaces. M3 `primaryContainer`. */
    val primaryWash: Color,
    /** "Good" rating, retention figures, streak band. */
    val success: Color,
    /** Success icon backgrounds. */
    val successWash: Color,
    /** "Hard" rating, `truncated` badge, critique text. */
    val warn: Color,
    /** Warn badge backgrounds. */
    val warnWash: Color,
    /** New-card counts, unread dot, critique left rule. M3 `tertiary`. */
    val accent: Color,
    /** "Again" rating, lapse counts, `needs_human` badge text. M3 `error`. */
    val danger: Color,
    /** Danger badge backgrounds. M3 `errorContainer`. */
    val dangerWash: Color,
)

val LightRecallyColorTokens =
    RecallyColorTokens(
        ground = Color(0xFFFFFFFF),
        // Light mode has no card fill (design-system.md) — cards are `ground`
        // defined by a hairline `line` border, so surface collapses onto ground.
        surface = Color(0xFFFFFFFF),
        ink = Color(0xFF17181A),
        inkStrong = Color(0xFF3A3A36),
        inkMuted = Color(0xFF57574F),
        inkSoft = Color(0xFF76766D),
        inkFaint = Color(0xFF94948D),
        line = Color(0xFFE3E3E0),
        lineSoft = Color(0xFFF0F0EE),
        track = Color(0xFFEDEDEA),
        neutralWash = Color(0xFFF4F4F2),
        primaryMuted = Color(0xFFCFE0DA),
        primary = Color(0xFF1F6F5C),
        primaryDark = Color(0xFF175646),
        primaryWash = Color(0xFFE4F0EB),
        success = Color(0xFF2F9E6E),
        successWash = Color(0xFFDBF0E6),
        warn = Color(0xFFB85C2E),
        warnWash = Color(0xFFF8E6D8),
        accent = Color(0xFFD97742),
        danger = Color(0xFFC0453A),
        dangerWash = Color(0xFFF7DEDB),
    )

val DarkRecallyColorTokens =
    RecallyColorTokens(
        ground = Color(0xFF121413),
        surface = Color(0xFF232725),
        ink = Color(0xFFECEFED),
        inkStrong = Color(0xFFC9CECB),
        inkMuted = Color(0xFFADB3B0),
        inkSoft = Color(0xFF8F9693),
        // Lifted from the naive equivalent — the straight port failed AA on surface.
        inkFaint = Color(0xFF8B928F),
        line = Color(0xFF2C302E),
        lineSoft = Color(0xFF2C302E),
        track = Color(0xFF2C302E),
        neutralWash = Color(0xFF232725),
        primaryMuted = Color(0xFF2B4A40),
        primary = Color(0xFF4FB894),
        primaryDark = Color(0xFF4FB894),
        primaryWash = Color(0xFF153A30),
        success = Color(0xFF4FC78F),
        successWash = Color(0xFF123528),
        warn = Color(0xFFE8975F),
        warnWash = Color(0xFF3A2A1C),
        accent = Color(0xFFE8975F),
        danger = Color(0xFFE8756A),
        dangerWash = Color(0xFF3A1F1C),
    )
