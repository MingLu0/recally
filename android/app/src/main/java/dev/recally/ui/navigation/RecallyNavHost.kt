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
import dev.recally.ui.screens.approve.ApproveScreen
import dev.recally.ui.screens.approve.ApproveViewModel
import dev.recally.ui.screens.decks.BookDetailScreen
import dev.recally.ui.screens.decks.DecksScreen
import dev.recally.ui.screens.decks.DecksViewModel
import dev.recally.ui.screens.review.ReviewScreen
import dev.recally.ui.screens.review.ReviewViewModel
import dev.recally.ui.screens.settings.SettingsScreen
import dev.recally.ui.screens.settings.SettingsViewModel
import dev.recally.ui.screens.stats.StatsScreen
import dev.recally.ui.screens.stats.StatsViewModel
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
            // Issue #147: the ViewModel survives on the back stack, so the
            // screen re-queries when it regains focus or connectivity flips.
            RefreshOnResumeEffect(todayViewModel::refresh)
            RefreshOnConnectivityChangeEffect(todayViewModel::refresh)

            TodayScreen(
                uiState = todayUiState,
                onStartReview = { navController.navigate(Screen.Review.route) },
                onOpenApprove = { navController.navigate(Screen.Approve.route) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onRetry = todayViewModel::refresh,
                onBookClick = navController::openBookDetail,
            )
        }
        composable(Screen.Review.route) {
            val reviewViewModel: ReviewViewModel = hiltViewModel()
            val reviewUiState by reviewViewModel.uiState.collectAsStateWithLifecycle()

            ReviewScreen(
                uiState = reviewUiState,
                onFlip = reviewViewModel::flip,
                onRate = reviewViewModel::rate,
                onBury = reviewViewModel::bury,
                onStartEdit = reviewViewModel::startEdit,
                onDismissEdit = reviewViewModel::dismissEdit,
                onEditCard = reviewViewModel::editCard,
                onClose = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onRetry = reviewViewModel::loadSession,
                onDone = { navController.popBackStack() },
            )
        }
        composable(Screen.SessionSummary.route) { }
        composable(Screen.Approve.route) {
            val approveViewModel: ApproveViewModel = hiltViewModel()
            val approveUiState by approveViewModel.uiState.collectAsStateWithLifecycle()

            ApproveScreen(
                uiState = approveUiState,
                onFilterChange = approveViewModel::onFilterChange,
                onToggleHighlights = approveViewModel::onToggleHighlights,
                onApproveCard = approveViewModel::approveCard,
                onStartEdit = approveViewModel::onStartEdit,
                onDismissEdit = approveViewModel::onDismissEdit,
                onEditCard = approveViewModel::editCard,
                onRejectCard = approveViewModel::rejectCard,
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateBack = { navController.popBackStack() },
                onRetry = approveViewModel::refresh,
                onApproveAllClean = approveViewModel::approveAllClean,
            )
        }
        composable(Screen.Decks.route) {
            val decksViewModel: DecksViewModel = hiltViewModel()
            val decksUiState by decksViewModel.uiState.collectAsStateWithLifecycle()
            RefreshOnResumeEffect(decksViewModel::refresh)
            RefreshOnConnectivityChangeEffect(decksViewModel::refresh)
            DecksScreen(
                uiState = decksUiState,
                onDeckClick = navController::openBookDetail,
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
        composable(Screen.Stats.route) {
            val statsViewModel: StatsViewModel = hiltViewModel()
            val statsUiState by statsViewModel.uiState.collectAsStateWithLifecycle()
            RefreshOnResumeEffect(statsViewModel::refresh)
            RefreshOnConnectivityChangeEffect(statsViewModel::refresh)

            StatsScreen(
                uiState = statsUiState,
                onRetry = statsViewModel::refresh,
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
            )
        }
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

/**
 * The single navigation decision for "open this book" (issue #180): the Today
 * rail and the Decks list both call it, so one book has one destination.
 */
fun NavHostController.openBookDetail(bookId: Long) {
    navigate(Screen.BookDetail.createRoute(bookId))
}
