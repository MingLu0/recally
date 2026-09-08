package dev.recally.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * Single NavHost for the app (docs/android.md, "Architecture"). Each route
 * entry will own its ViewModel (hiltViewModel) and state collection once the
 * screens land in the step 4b+ issues; this issue wires the routes only.
 */
@Composable
fun RecallyNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Today.route,
        modifier = Modifier.padding(contentPadding),
    ) {
        composable(Screen.Today.route) { }
        composable(Screen.Review.route) { }
        composable(Screen.SessionSummary.route) { }
        composable(Screen.Approve.route) { }
        composable(Screen.Decks.route) { }
        composable(
            route = Screen.BookDetail.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) { }
        composable(Screen.Stats.route) { }
        composable(Screen.Settings.route) { }
    }
}
