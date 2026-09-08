package dev.recally.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Rewrites each request's scheme/host/port to the base URL entered in
 * Settings. The base URL is a runtime value, so it cannot be Retrofit's
 * compile-time `baseUrl(...)`; the service is built against an unroutable
 * placeholder ([RecallyApiFactory.PLACEHOLDER_BASE_URL]) and this interceptor
 * points every call at the configured server. Path segments in the configured
 * URL are not supported — the backend serves the API at the root.
 *
 * With no base URL configured the request proceeds against the placeholder
 * and fails as a network error, which the Settings screen already reports as
 * "server not reachable — check the URL".
 */
class BaseUrlInterceptor(
    private val settings: ConnectionSettingsProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val configured =
            settings.baseUrl()?.trim()?.toHttpUrlOrNull()
                ?: return chain.proceed(chain.request())
        val rewritten =
            chain
                .request()
                .url
                .newBuilder()
                .scheme(configured.scheme)
                .host(configured.host)
                .port(configured.port)
                .build()
        return chain.proceed(
            chain
                .request()
                .newBuilder()
                .url(rewritten)
                .build(),
        )
    }
}
