package dev.recally.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends `X-API-Key` on every request (docs/api-spec.md). The key comes from
 * runtime settings, never from the build. A missing key sends no header and
 * lets the server's 401 surface as [dev.recally.domain.repository.Result.Unauthorized]
 * — the Settings screen's prompt to configure one.
 */
class ApiKeyInterceptor(
    private val settings: ConnectionSettingsProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val apiKey = settings.apiKey()
        if (apiKey.isNullOrBlank()) {
            return chain.proceed(chain.request())
        }
        val authenticated =
            chain
                .request()
                .newBuilder()
                .header("X-API-Key", apiKey)
                .build()
        return chain.proceed(authenticated)
    }
}
