package dev.recally.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.recally.data.remote.RecallyApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Base URL is entered at runtime in Settings and stored encrypted
 * (docs/android.md, "Connecting to the backend") — nothing usable is baked
 * into the build. Until step 4c/4e wire the stored URL and the X-API-Key
 * interceptor, the graph builds against an RFC 2606 `.invalid` sentinel so
 * any call fails fast with an unknown host rather than silently reaching a
 * real server.
 */
private const val UNCONFIGURED_BASE_URL = "http://recally-backend.unconfigured.invalid/"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideRetrofit(
        json: Json,
        client: OkHttpClient,
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(UNCONFIGURED_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideRecallyApi(retrofit: Retrofit): RecallyApi = retrofit.create(RecallyApi::class.java)
}
