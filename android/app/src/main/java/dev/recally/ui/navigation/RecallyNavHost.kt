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
import dev.recally.ui.screens.decks.BookDetailScreen
import dev.recally.ui.screens.decks.DecksScreen
import dev.recally.ui.screens.decks.DecksViewModel
import dev.recally.ui.screens.settings.SettingsScreen
import dev.recally.ui.screens.settings.SettingsViewModel
import dev.recally.ui.screens.today.TodayScreen
import dev.recally.ui.screens.today.TodayViewModel

/**
 * Single NavHost for the app (docs/android.md, "Architecture"). Each route
 * entry owns its ViewModel (hiltViewModel) and state collection; screens stay
 * pure composables with UiState in and callbacks out. The decks routes share
 * [DecksViewModel], scoped per route entry — the parameterised
 * `decks/{bookId}` route gets its `bookId` from the SavedStateHandle.
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
        composable(Screen.Today.route) {
            val todayViewModel: TodayViewModel = hiltViewModel()
            val todayUiState by todayViewModel.uiState.collectAsStateWithLifecycle()

            TodayScreen(
                uiState = todayUiState,
                onStartReview = { navController.navigate(Screen.Review.route) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onRetry = todayViewModel::refresh,
            )
        }
        composable(Screen.Review.route) { }
        composable(Screen.SessionSummary.route) { }
        composable(Screen.Approve.route) { }
        composable(Screen.Decks.route) {
            val decksViewModel: DecksViewModel = hiltViewModel()
            val decksUiState by decksViewModel.uiState.collectAsStateWithLifecycle()
            DecksScreen(
                uiState = decksUiState,
                onDeckClick = { bookId -> navController.navigate(Screen.BookDetail.createRoute(bookId)) },
                onRetry = decksViewModel::refresh,
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
            )
        }
        composable(
            route = Screen.BookDetail.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) {
            val bookDetailViewModel: DecksViewModel = hiltViewModel()
            val bookDetailUiState by bookDetailViewModel.uiState.collectAsStateWithLifecycle()
            BookDetailScreen(
                uiState = bookDetailUiState,
                onBack = { navController.popBackStack() },
                onChapterToggled = bookDetailViewModel::toggleChapter,
                onStartEdit = bookDetailViewModel::startEdit,
                onDismissEdit = bookDetailViewModel::dismissEdit,
                onSubmitEdit = bookDetailViewModel::submitEdit,
                onSuspendCard = bookDetailViewModel::suspendCard,
                onUnsuspendCard = bookDetailViewModel::unsuspendCard,
                onRetry = bookDetailViewModel::refresh,
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
            )
        }
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
