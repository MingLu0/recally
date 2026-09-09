package dev.recally.ui.navigation

import android.content.Intent
import androidx.navigation.NavHostController

/**
 * The FCM deep link (docs/android.md, "Push notifications"). The notification
 * carries no card ids — only the destination route — because anything else
 * would be stale by the time it is tapped; Today refetches.
 *
 * Intent extra the due-cards notification sets on its tap intent:
 */
const val EXTRA_DEEP_LINK_ROUTE = "dev.recally.extra.DEEP_LINK_ROUTE"

/** The route a notification tap asks for, or null when the intent is not one. */
fun Intent.deepLinkRoute(): String? = getStringExtra(EXTRA_DEEP_LINK_ROUTE)

/** Route a notification tap to Today. */
fun NavHostController.navigateToTodayDeepLink() {
    navigate(Screen.Today.route)
}
