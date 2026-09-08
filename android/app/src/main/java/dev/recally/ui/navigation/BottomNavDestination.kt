package dev.recally.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import dev.recally.R

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
    TODAY(Screen.Today, R.string.nav_today, Icons.Filled.DateRange),
    DECKS(Screen.Decks, R.string.nav_decks, Icons.Filled.List),
    STATS(Screen.Stats, R.string.nav_stats, Icons.Filled.Star),
    SETTINGS(Screen.Settings, R.string.nav_settings, Icons.Filled.Settings),
}

/**
 * The bar belongs to the four top-level destinations. Review, Approve, the
 * session summary and the pushed book-detail screen hide it: each is a task
 * you finish and leave, and book detail is a second level under Decks.
 */
fun shouldShowBottomBar(currentRoute: String?): Boolean =
    BottomNavDestination.entries.any { destination -> destination.screen.route == currentRoute }
