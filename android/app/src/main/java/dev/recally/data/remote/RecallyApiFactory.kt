package dev.recally.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds the [RecallyApi] against runtime settings. Hilt wiring lands with
 * the Settings store (step 4e), which owns the [ConnectionSettingsProvider]
 * implementation; until then callers construct the client through here.
 */
object RecallyApiFactory {
    /**
     * Unroutable stand-in for Retrofit's mandatory base URL — every real
     * request is rewritten to the configured base URL by [BaseUrlInterceptor],
     * so a settings regression surfaces as a connection failure, never as a
     * call to a baked-in host.
     */
    const val PLACEHOLDER_BASE_URL = "http://recally.invalid/"

    /**
     * `ignoreUnknownKeys` so a server-side field addition cannot break the
     * client; `explicitNulls = false` so optional request fields (PATCH edits,
     * `device_id`) are omitted from the body rather than sent as null.
     */
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun create(settings: ConnectionSettingsProvider): RecallyApi {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(ApiKeyInterceptor(settings))
                .addInterceptor(BaseUrlInterceptor(settings))
                .build()
        return Retrofit
            .Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RecallyApi::class.java)
    }
}
