package dev.memoh.core.network

import dev.memoh.core.model.Bot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Credential handling is where a naive client breaks: the server issues a short
 * JWT with no refresh token, so a request that arrives after expiry cannot be
 * renewed at all. These tests pin the pre-emptive refresh, the single-flight
 * retry, and the "give up and log out" path.
 */
class MemohApiAuthTest {

    @Test fun `media uses account headers refreshes once and returns binary bytes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0, 1, -1))))
        val auth = FakeAuth()
        assertTrue(api(auth).media("/bots/b/media/file").contentEquals(byteArrayOf(0, 1, -1)))
        assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))
        val retry = server.takeRequest()
        assertEquals("/bots/b/media/file", retry.path)
        assertEquals("Bearer token-2", retry.getHeader("Authorization"))
        assertEquals(1, auth.refreshCount.get())
    }

    @Test fun `chunked media with unknown content length reads through EOF`() = runTest {
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(byteArrayOf(1, 2, 3, 4)), 2))
        assertTrue(api(FakeAuth()).media("/bots/b/media/file").contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun `media redirects cannot forward credentials to another server`() = runTest {
        MockWebServer().use { other ->
            other.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/leak")))
            val failure = runCatching { api(FakeAuth()).media("/bots/b/media/file") }.exceptionOrNull()
            assertTrue(failure is ApiException)
            assertEquals(0, other.requestCount)
        }
    }

    @Test fun `inline media does not need a credential or network exchange`() = runTest {
        assertEquals("image", api(null).media("data:image/png;base64,aW1hZ2U=").decodeToString())
        assertEquals(0, server.requestCount)
        assertTrue(runCatching { api(null).media("http://external.example/image.png") }.exceptionOrNull() is IllegalArgumentException)
    }

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Records every refresh call so we can assert how many actually happened. */
    private class FakeAuth(
        @Volatile var token: String = "token-1",
        @Volatile var expiresAt: Long? = null,
        @Volatile var refreshed: String? = "token-2",
    ) : AuthProvider {
        val refreshCount = AtomicInteger(0)
        val expiredCount = AtomicInteger(0)
        var lastLease: AuthLease? = AuthLease(token, expiresAt, generation = 1L)

        override fun currentLease(): AuthLease? = lastLease

        override suspend fun refresh(previous: AuthLease): AuthLease? {
            refreshCount.incrementAndGet()
            val next = refreshed ?: return null
            val lease = AuthLease(next, null, previous.generation + 1)
            lastLease = lease
            return lease
        }

        override fun onSessionExpired() {
            expiredCount.incrementAndGet()
        }
    }

    private fun api(auth: AuthProvider?) = MemohApi(
        client = OkHttpClient(),
        endpoint = ServerEndpoint(
            kind = ServerKind.SelfHosted,
            origin = server.url("/").toString().trimEnd('/'),
            apiPrefix = "",
            memohPrefix = "",
        ),
        json = json,
        auth = auth,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a successful request sends the bearer token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[]}""").setResponseCode(200))
        val auth = FakeAuth()
        api(auth).bots()

        val recorded = server.takeRequest()
        assertEquals("Bearer token-1", recorded.getHeader("Authorization"))
    }

    @Test
    fun `a 401 triggers one refresh and one retry that succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"expired"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))

        val auth = FakeAuth()
        val result = api(auth).bots()

        assertTrue(result.isEmpty())
        assertEquals(1, auth.refreshCount.get())
        assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a persistent 401 gives up and reports the session expired`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val auth = FakeAuth()
        try {
            api(auth).bots()
            throw AssertionError("expected SessionExpiredException")
        } catch (expected: SessionExpiredException) {
            // Retried exactly once, then gave up rather than looping.
            assertEquals(1, auth.refreshCount.get())
            assertEquals(1, auth.expiredCount.get())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `an unrefreshable session expires immediately`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val auth = FakeAuth(refreshed = null)
        try {
            api(auth).bots()
            throw AssertionError("expected SessionExpiredException")
        } catch (expected: SessionExpiredException) {
            assertEquals(1, auth.expiredCount.get())
            // Only the original request; there is nothing to retry with.
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a lease near expiry is refreshed before the request is sent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))

        // Expires in 10s, inside the 60s skew window.
        val auth = FakeAuth(expiresAt = System.currentTimeMillis() + 10_000)
        api(auth).bots()

        assertEquals(1, auth.refreshCount.get())
        // The refresh happened first, so the very first request already carries
        // the new token — no wasted round trip on a doomed request.
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a lease far from expiry is used as-is`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))

        val auth = FakeAuth(expiresAt = System.currentTimeMillis() + 3_600_000)
        api(auth).bots()

        assertEquals(0, auth.refreshCount.get())
        assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `concurrent 401s cause exactly one refresh`() = runTest {
        // Answer by credential rather than FIFO: the first token is always
        // rejected, the refreshed one always accepted. A queue of scripted
        // responses would hand a retry someone else's 401 and prove nothing.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val authorized = request.getHeader("Authorization") == "Bearer token-2"
                return if (authorized) {
                    MockResponse().setResponseCode(200).setBody("""{"items":[]}""")
                } else {
                    MockResponse().setResponseCode(401)
                }
            }
        }

        val auth = FakeAuth()
        val api = api(auth)
        val results = (1..5).map { async { api.bots() } }.awaitAll()

        assertTrue(results.all { it.isEmpty() })
        assertEquals(
            "five concurrent 401s must collapse into one refresh",
            1,
            auth.refreshCount.get(),
        )
        assertEquals(0, auth.expiredCount.get())
    }

    @Test
    fun `a request with no credentials fails without touching the network`() = runTest {
        try {
            api(auth = null).bots()
            throw AssertionError("expected SessionExpiredException")
        } catch (expected: SessionExpiredException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `ping needs no credentials`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"status":"ok","version":"1.2.3","container_backend":"docker"}"""),
        )
        val ping = api(auth = null).ping()

        assertTrue(ping.isOk)
        assertEquals("1.2.3", ping.version)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a non-401 failure surfaces the status and body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val auth = FakeAuth()
        try {
            api(auth).bots()
            throw AssertionError("expected ApiException")
        } catch (expected: ApiException) {
            assertEquals(500, expected.status)
            assertTrue(expected.body.orEmpty().contains("boom"))
            // A 500 is not an auth problem, so credentials stay untouched.
            assertEquals(0, auth.refreshCount.get())
            assertEquals(0, auth.expiredCount.get())
        }
    }

    @Test
    fun `an unknown field in the payload does not break decoding`() = runTest {
        // Additive server changes must not crash the client.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":"bot-1","name":"n","brand_new_field":42}]}""",
            ),
        )
        val bots: List<Bot> = api(FakeAuth()).bots()
        assertEquals(1, bots.size)
        assertEquals("bot-1", bots.first().id)
    }
}
