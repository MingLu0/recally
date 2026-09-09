package dev.recally

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.recally.data.sync.RatingOutboxWork
import dev.recally.ui.AppScaffold
import dev.recally.ui.navigation.RecallyNavHost
import dev.recally.ui.navigation.Screen
import dev.recally.ui.navigation.deepLinkRoute
import dev.recally.ui.navigation.navigateToBottomNavDestination
import dev.recally.ui.navigation.navigateToTodayDeepLink
import dev.recally.ui.theme.RecallyTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Entry point: theme, nav controller, deep-link intent (docs/android.md,
 * "Architecture"). FCM deep links open Today — the notification names a due
 * count and Today is where that count is actionable (docs/android.md, "Push
 * notifications"). The activity is `singleTop`, so a tap lands in [onNewIntent]
 * when the app is already open, and the nav helper never stacks a second Today.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Routes delivered by notification taps, consumed by the NavHost. */
    private val pendingDeepLinkRoute = MutableStateFlow<String?>(null)

    // POST_NOTIFICATIONS is a runtime permission from API 33. Today works
    // whether it is granted or denied, so the result needs no handling.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The outbox flush is expedited on app start and enqueued after each
        // rating (docs/android.md, "Offline-first sync").
        RatingOutboxWork.enqueue(this, expedited = true)
        handleDeepLinkIntent(intent)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            RecallyTheme {
                val navController = rememberNavController()
                LaunchedEffect(Unit) {
                    pendingDeepLinkRoute.collect { route ->
                        if (route == Screen.Today.route) {
                            navController.navigateToTodayDeepLink()
                        }
                        pendingDeepLinkRoute.value = null
                    }
                }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        intent?.deepLinkRoute()?.let { pendingDeepLinkRoute.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
