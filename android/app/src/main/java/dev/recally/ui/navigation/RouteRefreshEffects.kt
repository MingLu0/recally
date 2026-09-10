package dev.recally.ui.navigation

import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Re-query when a surviving screen regains focus (issue #147). A route entry's
 * ViewModel loads in its `init` block and survives on the back stack while
 * another route is pushed over it, so without this the screen would render
 * that first load forever — Today still claiming a card due after the review
 * session finished it. Navigation Compose sets the entry as the local
 * lifecycle owner, so ON_RESUME fires again exactly when the screen returns
 * to the top (and when the app itself comes back from the background).
 *
 * The first ON_RESUME is skipped: it arrives with the initial composition,
 * whose load the ViewModel's `init` already owns — firing here too would
 * fetch twice on every cold entry.
 *
 * Returns the observer so a caller can detach it; the composable wrapper
 * [RefreshOnResumeEffect] detaches on dispose.
 */
fun LifecycleOwner.refreshOnResume(onResumeRefresh: () -> Unit): LifecycleEventObserver {
    var initialResumeSeen = false
    val observer =
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (initialResumeSeen) {
                    onResumeRefresh()
                } else {
                    initialResumeSeen = true
                }
            }
        }
    lifecycle.addObserver(observer)
    return observer
}

/** [refreshOnResume] as an effect for a `NavHost` route entry. */
@Composable
fun RefreshOnResumeEffect(onResumeRefresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResumeRefresh by rememberUpdatedState(onResumeRefresh)
    DisposableEffect(lifecycleOwner) {
        val observer = lifecycleOwner.refreshOnResume { currentOnResumeRefresh() }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Re-query when connectivity flips across the online/offline boundary
 * (issue #147). A screen that is already loaded when aeroplane mode toggles
 * never learns of it from a lifecycle resume — the activity stays resumed
 * under the quick-settings shade — so the offline bar and the recovery ride
 * the network callback instead. The refresh's own result still decides the
 * flag: a doomed fetch fails with `Result.NetworkError` (or answers from the
 * Room cache with `servedFromCache`), which is what sets `isOffline`.
 *
 * Only boundary crossings fire: losing one of several networks (Wi-Fi
 * dropping while mobile data stays) does not re-query.
 */
@Composable
fun RefreshOnConnectivityChangeEffect(onConnectivityChange: () -> Unit) {
    val context = LocalContext.current
    val currentOnConnectivityChange by rememberUpdatedState(onConnectivityChange)
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return@DisposableEffect onDispose { }
        val activeNetworks = mutableSetOf<Network>()
        connectivityManager.activeNetwork?.let(activeNetworks::add)
        var wasOnline = activeNetworks.isNotEmpty()

        fun onBoundaryCrossed() {
            val online = activeNetworks.isNotEmpty()
            if (online != wasOnline) {
                wasOnline = online
                currentOnConnectivityChange()
            }
        }

        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    activeNetworks += network
                    onBoundaryCrossed()
                }

                override fun onLost(network: Network) {
                    activeNetworks -= network
                    onBoundaryCrossed()
                }
            }
        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
