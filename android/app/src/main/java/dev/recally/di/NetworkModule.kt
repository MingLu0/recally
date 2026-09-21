package dev.recally.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.recally.data.remote.ConnectionSettingsProvider
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.RecallyApiFactory
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The [RecallyApi] the upload client uses. `POST /ingest` answers only after
 * the server-side pipeline has run, so it rides a client with a long read
 * timeout; every other endpoint keeps OkHttp's defaults.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IngestApi

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

    @Provides
    @Singleton
    @IngestApi
    fun provideIngestApi(settings: ConnectionSettingsProvider): RecallyApi = RecallyApiFactory.createIngest(settings)
}
