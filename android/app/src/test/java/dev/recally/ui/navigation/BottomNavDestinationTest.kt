package dev.recally.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bottom-navigation gate. The source of truth is docs/android.md
 * ("Navigation") and docs/design/design-system.md ("Bottom navigation") —
 * four items, and Review/Approve deliberately absent.
 */
class BottomNavDestinationTest {
    @Test
    fun test_bottom_nav_has_the_four_documented_destinations_in_order() {
        // docs/android.md, "Navigation": Today, Decks, Stats, Settings.
        assertEquals(
            listOf(Screen.Today, Screen.Decks, Screen.Stats, Screen.Settings),
            BottomNavDestination.entries.map { it.screen },
        )
    }

    @Test
    fun test_review_and_approve_are_not_nav_destinations() {
        // Both are modal tasks entered from Today; a permanent nav seat would
        // invite abandoning a session mid-task (docs/android.md,
        // "Navigation").
        val routes = BottomNavDestination.entries.map { it.screen.route }
        assertFalse(routes.contains(Screen.Review.route))
        assertFalse(routes.contains(Screen.Approve.route))
        assertFalse(routes.contains(Screen.SessionSummary.route))
    }

    @Test
    fun test_bar_is_shown_only_on_the_four_top_level_routes() {
        // The bar belongs to top-level destinations. Modal tasks and the
        // pushed book-detail screen hide it.
        for (destination in BottomNavDestination.entries) {
            assertTrue(
                "${destination.screen.route} is a top-level route",
                shouldShowBottomBar(destination.screen.route),
            )
        }
        assertFalse(shouldShowBottomBar(Screen.Review.route))
        assertFalse(shouldShowBottomBar(Screen.Approve.route))
        assertFalse(shouldShowBottomBar(Screen.SessionSummary.route))
        assertFalse(shouldShowBottomBar(Screen.BookDetail.route))
        assertFalse(shouldShowBottomBar(Screen.BookDetail.createRoute(42L)))
        assertFalse(shouldShowBottomBar(null))
    }

    @Test
    fun test_approve_is_reachable_even_though_it_is_not_a_nav_destination() {
        // docs/android.md, "Navigation": Review and Approve are entered *from*
        // Today. Keeping Approve off the bottom bar is correct, but the route
        // then has no entrance unless Today offers one — it was registered in
        // the NavHost and navigated to by nothing, so the whole approval queue
        // was dead code in the shipped app.
        val todayScreenSource =
            java.io.File("src/main/java/dev/recally/ui/screens/today/TodayScreen.kt").readText()
        val navHostSource =
            java.io.File("src/main/java/dev/recally/ui/navigation/RecallyNavHost.kt").readText()

        assertTrue(
            "TodayScreen must expose an approval-queue callback",
            "onOpenApprove" in todayScreenSource,
        )
        assertTrue(
            "the Today route must navigate to Screen.Approve",
            "Screen.Approve" in navHostSource && "onOpenApprove" in navHostSource,
        )
    }

    @Test
    fun test_every_destination_carries_a_label_and_distinct_icons() {
        // The spec draws an active filled-circle glyph and an inactive stroke
        // glyph per item (design-system.md, "Bottom navigation").
        for (destination in BottomNavDestination.entries) {
            assertTrue(
                "${destination.name} has a label",
                destination.labelRes != 0,
            )
        }
        val icons = BottomNavDestination.entries.map { it.icon }
        assertEquals("each destination has its own icon", icons.size, icons.toSet().size)
    }
}
