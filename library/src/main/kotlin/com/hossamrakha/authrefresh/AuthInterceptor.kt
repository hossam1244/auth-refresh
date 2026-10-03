package com.hossamrakha.authrefresh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Attaches the Bearer token and performs a single, lock-guarded refresh on 401.
 *
 * Rotating refresh tokens can only be used once, so concurrent 401s must share
 * one refresh. All of them wait on [refreshLock] while the first thread
 * performs it; threads that arrive after a completed refresh see a newer
 * access token and replay immediately without refreshing again.
 *
 * Each failed request is replayed **exactly once** (tagged), and a failed
 * refresh clears the store and fires [onSessionExpired].
 *
 * @param refreshUrl the absolute token-refresh endpoint. It is called on a
 *   bare [OkHttpClient] (no interceptor → no recursion), POSTing
 *   `{"refresh": "..."}` and expecting `{"access": "...", "refresh": "..."}`.
 * @param authPaths request-path substrings that must never trigger a refresh
 *   (login, register, the refresh endpoint itself…).
 * @param onSessionExpired fired exactly once when a refresh fails.
 */
class AuthInterceptor(
    private val tokenStore: TokenStore,
    private val refreshUrl: String,
    private val authPaths: Set<String> = emptySet(),
    var onSessionExpired: (() -> Unit)? = null,
) : Interceptor {

    private val refreshLock = ReentrantLock()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().withBearer()
        val response = chain.proceed(request)

        if (response.code != 401 || request.isAuthEndpoint()) {
            return response
        }
        if (request.tag(Retried::class.java) != null) {
            return response // one replay per request — never loop
        }

        val refreshed = refreshLock.withLock {
            // Another thread may have refreshed while we waited for the lock.
            val current = tokenStore.accessToken()
            val sentHeader = request.header("Authorization")?.removePrefix("Bearer ")
            if (current != null && current != sentHeader) {
                true
            } else {
                performRefresh()
            }
        }
        if (!refreshed) {
            tokenStore.clear()
            onSessionExpired?.invoke()
            return response
        }

        response.close()
        val retried = chain.call().request().newBuilder()
            .tag(Retried::class.java, Retried)
            .build()
            .withBearer()
        return chain.proceed(retried)
    }

    private fun Request.withBearer(): Request {
        val token = tokenStore.accessToken() ?: return this
        return newBuilder().header("Authorization", "Bearer $token").build()
    }

    private fun Request.isAuthEndpoint(): Boolean =
        authPaths.any { url.encodedPath.contains(it) }

    /** POSTs the refresh endpoint on a bare client (no interceptor loop).
     *  Blocking is fine here: interceptors run on OkHttp's network threads. */
    private fun performRefresh(): Boolean {
        val refresh = tokenStore.refreshToken() ?: return false
        val body = Json.encodeToString(RefreshPayload.serializer(), RefreshPayload(refresh))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(refreshUrl).post(body).build()
        return try {
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val parsed = Json.decodeFromString(
                    RefreshResponse.serializer(),
                    response.body?.string().orEmpty(),
                )
                if (parsed.access.isEmpty()) return false
                tokenStore.save(parsed.access, parsed.refresh ?: refresh)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    @Serializable
    private data class RefreshPayload(val refresh: String)

    @Serializable
    private data class RefreshResponse(val access: String = "", val refresh: String? = null)

    private object Retried
}
