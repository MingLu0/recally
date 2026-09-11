package dev.recally.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import dev.recally.R
import dev.recally.ui.theme.NavBarChartIcon
import dev.recally.ui.theme.NavBookIcon
import dev.recally.ui.theme.NavHomeIcon
import dev.recally.ui.theme.NavSettingsIcon

/**
 * The four bottom-navigation destinations (docs/android.md, "Navigation";
 * design-system.md, "Bottom navigation"). Review and Approve are entered from
 * Today rather than sitting here — both are modal tasks you finish and leave,
 * so a permanent nav seat would invite abandoning a session mid-task.
 */
enum class BottomNavDestination(
    val screen: Screen,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    TODAY(Screen.Today, R.string.nav_today, NavHomeIcon),
    DECKS(Screen.Decks, R.string.nav_decks, NavBookIcon),
    STATS(Screen.Stats, R.string.nav_stats, NavBarChartIcon),
    SETTINGS(Screen.Settings, R.string.nav_settings, NavSettingsIcon),
}

/**
 * The bar belongs to the four top-level destinations. Review, Approve, the
 * session summary and the pushed book-detail screen hide it: each is a task
 * you finish and leave, and book detail is a second level under Decks.
 */
fun shouldShowBottomBar(currentRoute: String?): Boolean =
    BottomNavDestination.entries.any { destination -> destination.screen.route == currentRoute }
