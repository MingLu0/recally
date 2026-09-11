package dev.recally.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Bottom-navigation icons (design-system.md, "Bottom navigation"; issue #198):
 * Lucide 24x24 stroke icons, `viewBox="0 0 24 24"`, round caps/joins, no fill.
 * Plain `ImageVector` values (not `@Composable`) so
 * [BottomNavDestination][dev.recally.ui.navigation.BottomNavDestination] can
 * hold one per enum entry; [RecallyBottomBar][dev.recally.ui.navigation.RecallyBottomBar]
 * tints them via [androidx.compose.material3.Icon], so the paths here carry a
 * placeholder stroke colour that the tint always overrides.
 */
private fun ImageVector.Builder.strokePath(
    svgPath: String,
    strokeWidth: Float = 1.9f,
) = addPath(
    pathData = addPathNodes(svgPath),
    stroke = SolidColor(Color.Black),
    strokeLineWidth = strokeWidth,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
)

/** Today: `home` (Lucide). */
val NavHomeIcon: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "nav_home",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).strokePath("M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
        .build()
}

/** Decks: `book` (Lucide) -- spine plus cover, two open paths. */
val NavBookIcon: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "nav_book",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).strokePath("M4 19.5A2.5 2.5 0 0 1 6.5 17H20")
        .strokePath("M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z")
        .build()
}

/** Stats: `bar-chart` (Lucide), heavier stroke per the artboard (2.2). */
val NavBarChartIcon: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "nav_bar_chart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).strokePath("M18 20V10", strokeWidth = 2.2f)
        .strokePath("M12 20V4", strokeWidth = 2.2f)
        .strokePath("M6 20V14", strokeWidth = 2.2f)
        .build()
}

// Lucide "settings" gear ring, verbatim (24x24 viewBox) -- kept as one line
// so the SVG path data is never accidentally altered by a line-join.
private const val GEAR_RING_PATH =
    "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1.08-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1.08 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.06a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.06a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"

/** Settings: `settings` (Lucide) -- centre dot plus the gear ring. */
val NavSettingsIcon: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "nav_settings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).strokePath("M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z")
        .strokePath(GEAR_RING_PATH)
        .build()
}
