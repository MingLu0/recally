package dev.recally.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale from docs/design/design-system.md, "Spacing, radius,
 * elevation". Named constants so screens never write a raw `dp` guess — pick
 * the nearest step instead.
 */
object RecallySpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val cardPadding: Dp = 14.dp
    val lg: Dp = 16.dp

    /** Screen horizontal padding, 20dp throughout. */
    val screenPadding: Dp = 20.dp

    /** Review-card interior padding. */
    val reviewCardPadding: Dp = 22.dp
    val xl: Dp = 26.dp
}

/** Corner radii. `pill` also covers circular icon backgrounds. */
object RecallyRadius {
    /** Badges, chart bar caps. */
    val sm: Dp = 5.dp

    /** Cards, buttons, tiles. */
    val md: Dp = 12.dp

    /** Bottom-sheet top corners. */
    val lg: Dp = 22.dp

    /** Progress bars, filter chips, circular icon backgrounds. */
    val pill: Dp = 999.dp
}

/**
 * 26dp below the nav bar and above sheet bottoms, for the gesture bar
 * (design-system.md, "Spacing, radius, elevation").
 */
val RecallyBottomInset: Dp = 26.dp
