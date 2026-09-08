package dev.recally.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/**
 * Standard bottom-nav switch: pop back to the start destination, saving and
 * restoring each tab's state, and never stack duplicates of the same tab. Tabs
 * are peers, so tapping between them must not grow the back stack.
 */
fun NavHostController.navigateToBottomNavDestination(destination: BottomNavDestination) {
    navigate(destination.screen.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
