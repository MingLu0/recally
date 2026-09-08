package dev.recally

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
 *
 * Also the WorkManager configuration source so the rating-outbox flush worker
 * is built by Hilt ([HiltWorkerFactory]); the default initializer is removed
 * in the manifest.
 */
@HiltAndroidApp
class RecallyApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    @DefaultDispatcher
    lateinit var defaultDispatcher: CoroutineDispatcher

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

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
