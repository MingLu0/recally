package dev.recally.ui.navigation

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

    data object Approve : Screen("approve")

    data object Decks : Screen("decks")

    data object BookDetail : Screen("decks/{bookId}") {
        fun createRoute(bookId: Long): String = "decks/$bookId"
    }

    data object Stats : Screen("stats")

    data object Settings : Screen("settings")
}
