package dev.recally.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.recally.BuildConfig
import dev.recally.data.remote.ConnectionSettingsProvider
import dev.recally.data.settings.HealthAuthConnectionTester
import dev.recally.data.settings.KeystoreSettingsCipher
import dev.recally.data.settings.SettingsCipher
import dev.recally.data.settings.SettingsStore
import dev.recally.domain.repository.ConnectionTester
import dev.recally.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Named
import javax.inject.Singleton

/**
 * The Keystore-encrypted DataStore behind Settings (docs/android.md,
 * "Connecting to the backend"). [SettingsStore] is bound to both its faces:
 * the suspend [SettingsRepository] the Settings ViewModel uses and the
 * synchronous [ConnectionSettingsProvider] the OkHttp interceptors use.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + ioDispatcher),
            produceFile = { context.preferencesDataStoreFile("recally_settings") },
        )

    @Provides
    @Singleton
    fun provideSettingsCipher(): SettingsCipher = KeystoreSettingsCipher()

    @Provides
    @Named("appVersion")
    fun provideAppVersion(): String = BuildConfig.VERSION_NAME
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindingsModule {
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(store: SettingsStore): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindConnectionSettingsProvider(store: SettingsStore): ConnectionSettingsProvider

    @Binds
    @Singleton
    abstract fun bindConnectionTester(tester: HealthAuthConnectionTester): ConnectionTester
}
