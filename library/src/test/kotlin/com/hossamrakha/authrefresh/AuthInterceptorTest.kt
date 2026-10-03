package com.hossamrakha.authrefresh

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FakeTokenStore : TokenStore {
    private var access: String? = null
    private var refresh: String? = null

    override fun accessToken(): String? = access
    override fun refreshToken(): String? = refresh
    override fun save(access: String, refresh: String?) {
        this.access = access
        this.refresh = refresh
    }

    override fun clear() {
        access = null
        refresh = null
    }
}

class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokens: FakeTokenStore
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokens = FakeTokenStore()
        tokens.save("stale-access", "valid-refresh")
        client = OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(
                    tokenStore = tokens,
                    refreshUrl = server.url("/auth/token/refresh/").toString(),
                    authPaths = setOf("/auth/token"),
                )
            )
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `attaches the bearer token`() {
        server.enqueue(MockResponse().setBody("{}"))

        client.newCall(Request.Builder().url(server.url("/me/")).build()).execute()

        assertEquals("Bearer stale-access", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `401 refreshes the token and replays the request`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{}}"""))
        server.enqueue(MockResponse().setBody("""{"access":"new-access","refresh":"new-refresh"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val response = client
            .newCall(Request.Builder().url(server.url("/accounts/")).build())
            .execute()

        assertEquals(200, response.code)
        assertEquals("new-access", tokens.accessToken())
        assertEquals("new-refresh", tokens.refreshToken())

        server.takeRequest()
        server.takeRequest()
        val replayed = server.takeRequest()
        assertEquals("Bearer new-access", replayed.getHeader("Authorization"))
        assertEquals("/accounts/", replayed.path)
    }

    @Test
    fun `failed refresh clears the session and fires the callback`() {
        var expired = 0
        val guarded = OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(
                    tokenStore = tokens,
                    refreshUrl = server.url("/auth/token/refresh/").toString(),
                    onSessionExpired = { expired += 1 },
                )
            )
            .build()

        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val response = guarded
            .newCall(Request.Builder().url(server.url("/accounts/")).build())
            .execute()

        assertEquals(401, response.code)
        assertEquals(1, expired)
        assertNull(tokens.accessToken())
        assertNull(tokens.refreshToken())
    }

    @Test
    fun `auth paths never trigger a refresh`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        client.newCall(Request.Builder().url(server.url("/auth/token/")).build()).execute()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a replay that 401s again surfaces instead of looping`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"access":"new-access"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val response = client
            .newCall(Request.Builder().url(server.url("/accounts/")).build())
            .execute()

        assertEquals(401, response.code)
        assertEquals(3, server.requestCount) // original + refresh + one replay
    }
}
