package dev.recally.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.recally.ui.screens.settings.SettingsScreen
import dev.recally.ui.screens.settings.SettingsViewModel

/**
 * Single NavHost for the app (docs/android.md, "Architecture"). Each route
 * entry owns its ViewModel (hiltViewModel) and state collection; screens stay
 * pure composables with UiState in and callbacks out.
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
        composable(Screen.Settings.route) {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()

            SettingsScreen(
                uiState = settingsUiState,
                onBaseUrlChange = settingsViewModel::onBaseUrlChange,
                onApiKeyChange = settingsViewModel::onApiKeyChange,
                onToggleApiKeyVisibility = settingsViewModel::onToggleApiKeyVisibility,
                onTestConnection = settingsViewModel::testConnection,
            )
        }
    }
}
