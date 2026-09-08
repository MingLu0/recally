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
     * The client is built once against the unroutable
     * [RecallyApiFactory.PLACEHOLDER_BASE_URL]; the interceptors rewrite every
     * request from the runtime settings bound in SettingsModule (the
     * Keystore-encrypted DataStore, step 4e). Nothing usable is baked into
     * the build.
     */
    @Provides
    @Singleton
    fun provideRecallyApi(settings: ConnectionSettingsProvider): RecallyApi = RecallyApiFactory.create(settings)
}
