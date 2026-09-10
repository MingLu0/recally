package dev.recally.ui.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument
import dev.recally.ui.screens.approve.QueueFilter

/** Query-argument name for Approve's starting filter (issue #178). */
const val ARG_FILTER = "filter"

/**
 * Approve's optional starting-filter argument. Optional with an [QueueFilter.ALL]
 * default, so every entry point that navigates to the bare route is unchanged.
 */
fun approveFilterArgument() =
    navArgument(ARG_FILTER) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }

/**
 * Sealed route definitions (docs/android.md, "Project structure"). Review and
 * Approve are entered from Today rather than being bottom-nav destinations —
 * both are modal tasks you finish and leave (design-system.md, "Bottom
 * navigation").
 */
sealed class Screen(
    val route: String,
) {
    data object Today : Screen("today")

    data object Review : Screen("review")

    data object SessionSummary : Screen("review/summary")

    /**
     * Approve carries an optional starting filter (issue #178): the Today
     * "need you" tile counts `needs_human` cards, so it must land on the
     * Needs-you filter rather than on a list where they are mixed in with
     * everything else. Omitting the argument keeps the All default.
     */
    data object Approve : Screen("approve?$ARG_FILTER={$ARG_FILTER}") {
        fun createRoute(filter: QueueFilter = QueueFilter.ALL): String =
            if (filter == QueueFilter.ALL) "approve" else "approve?$ARG_FILTER=${filter.name}"
    }

    data object Decks : Screen("decks")

    data object BookDetail : Screen("decks/{bookId}") {
        fun createRoute(bookId: Long): String = "decks/$bookId"
    }

    data object Stats : Screen("stats")

    data object Settings : Screen("settings")
}
