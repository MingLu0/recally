package dev.recally.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.recally.ui.navigation.BottomNavDestination
import dev.recally.ui.navigation.RecallyBottomBar
import dev.recally.ui.navigation.shouldShowBottomBar

/**
 * Material3 Scaffold + snackbar host + bottom navigation (docs/android.md,
 * "Architecture", "Navigation"). The single snackbar host for the app —
 * screens raise events, the scaffold shows them.
 *
 * The bottom bar shows only on the four top-level destinations; Review,
 * Approve and book detail hide it (see [shouldShowBottomBar]).
 */
@Composable
fun AppScaffold(
    currentRoute: String?,
    onDestinationSelected: (BottomNavDestination) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (shouldShowBottomBar(currentRoute)) {
                RecallyBottomBar(
                    currentRoute = currentRoute,
                    onDestinationSelected = onDestinationSelected,
                )
            }
        },
        content = content,
    )
}
