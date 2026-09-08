package dev.recally.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Material3 Scaffold + snackbar host (docs/android.md, "Architecture"). The
 * single snackbar host for the app — screens raise events, the scaffold shows
 * them.
 */
@Composable
fun AppScaffold(content: @Composable (PaddingValues) -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        content = content,
    )
}
