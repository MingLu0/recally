package dev.recally

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.recally.data.settings.SettingsStore
import dev.recally.di.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt application (docs/android.md, "Architecture"). The composition root
 * for the app; modules live under `di/`.
 */
@HiltAndroidApp
class RecallyApplication : Application() {
    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    @DefaultDispatcher
    lateinit var defaultDispatcher: CoroutineDispatcher

    private val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + defaultDispatcher)
    }

    override fun onCreate() {
        super.onCreate()
        // Warm the synchronous interceptor face (ConnectionSettingsProvider)
        // before the first API call: the OkHttp interceptors cannot suspend,
        // so the decrypted cache must be loaded ahead of them. A failed first
        // load leaves the cache empty and Settings reports the failure.
        applicationScope.launch(CoroutineExceptionHandler { _, _ -> }) {
            settingsStore.load()
        }
    }
}
