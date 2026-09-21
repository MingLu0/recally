package dev.recally.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

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
     * The upload client's read timeout (issue #234). `POST /ingest` answers
     * only after the server-side agent pipeline has run — minutes for a large
     * export — while every other endpoint answers in milliseconds. OkHttp
     * timeouts are per-client, not per-request, so this call gets its own
     * client rather than stretching the default for the whole API.
     */
    val INGEST_READ_TIMEOUT: Duration = Duration.ofMinutes(15)

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

    fun create(settings: ConnectionSettingsProvider): RecallyApi = build(settings, readTimeout = null)

    /** The `POST /ingest` client: same interceptors, a read timeout that outlasts the pipeline. */
    fun createIngest(settings: ConnectionSettingsProvider): RecallyApi = build(settings, readTimeout = INGEST_READ_TIMEOUT)

    private fun build(
        settings: ConnectionSettingsProvider,
        readTimeout: Duration?,
    ): RecallyApi {
        val client =
            OkHttpClient
                .Builder()
                .apply {
                    if (readTimeout != null) readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
                }.addInterceptor(ApiKeyInterceptor(settings))
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
