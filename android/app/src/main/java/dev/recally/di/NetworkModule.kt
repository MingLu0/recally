package dev.recally.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.recally.data.remote.ConnectionSettingsProvider
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.RecallyApiFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /**
     * Placeholder until step 4e binds the Keystore-encrypted DataStore
     * implementation (docs/android.md, "Connecting to the backend"). Both
     * values unset: every request goes to [RecallyApiFactory.PLACEHOLDER_BASE_URL]
     * and fails as a network error — nothing usable is baked into the build.
     */
    @Provides
    @Singleton
    fun provideConnectionSettingsProvider(): ConnectionSettingsProvider =
        object : ConnectionSettingsProvider {
            override fun apiKey(): String? = null

            override fun baseUrl(): String? = null
        }

    @Provides
    @Singleton
    fun provideRecallyApi(settings: ConnectionSettingsProvider): RecallyApi = RecallyApiFactory.create(settings)
}
