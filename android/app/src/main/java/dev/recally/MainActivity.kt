package dev.recally

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.recally.data.sync.RatingOutboxWork
import dev.recally.ui.AppScaffold
import dev.recally.ui.navigation.RecallyNavHost
import dev.recally.ui.navigation.navigateToBottomNavDestination
import dev.recally.ui.theme.RecallyTheme

/**
 * Entry point: theme, nav controller, deep-link intent (docs/android.md,
 * "Architecture"). FCM deep links open Today — the notification names a due
 * count and Today is where that count is actionable (docs/android.md, "Push
 * notifications"). The nav controller owns the deep link from there.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The outbox flush is expedited on app start and enqueued after each
        // rating (docs/android.md, "Offline-first sync").
        RatingOutboxWork.enqueue(this, expedited = true)
        enableEdgeToEdge()
        setContent {
            RecallyTheme {
                val navController = rememberNavController()
                val currentBackStackEntry by navController.currentBackStackEntryAsState()
                AppScaffold(
                    currentRoute = currentBackStackEntry?.destination?.route,
                    onDestinationSelected = { destination ->
                        navController.navigateToBottomNavDestination(destination)
                    },
                ) { innerPadding ->
                    RecallyNavHost(
                        navController = navController,
                        contentPadding = innerPadding,
                    )
                }
            }
        }
    }
}
