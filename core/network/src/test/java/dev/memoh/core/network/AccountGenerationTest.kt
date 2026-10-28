package dev.memoh.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AccountGenerationTest {
    private class Auth : AuthProvider {
        @Volatile var lease = AuthLease("account-A", null, 1)
        var refreshes = 0
        var expirations = 0
        var refreshAction: () -> AuthLease? = { lease.copy(token = "refreshed-A") }
        override fun currentLease() = lease
        override suspend fun refresh(previous: AuthLease): AuthLease? {
            refreshes++
            return refreshAction()?.also { lease = it }
        }
        override fun onSessionExpired(generation: Long) {
            if (generation == this.generation) expirations++
        }
        fun switchAccount() { lease = AuthLease("account-B", null, 2) }
    }

    private fun api(server: MockWebServer, auth: Auth) = MemohApi(OkHttpClient(),
        ServerEndpoint(ServerKind.SelfHosted, server.url("/").toString().trimEnd('/'), "", ""),
        Json { ignoreUnknownKeys = true }, auth)

    private fun switchBeforeResponse(code: Int = 401, heldRequest: Int = 1,
        operation: suspend MemohApi.(String) -> Any?) = runBlocking {
        MockWebServer().use { server ->
            val received = CountDownLatch(1)
            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            val auth = Auth()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val number = requests.incrementAndGet()
                    if (number == heldRequest) {
                        received.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    return MockResponse().setResponseCode(code).setBody("""{"items":[]}""")
                }
            }
            server.start()
            val client = api(server, auth)
            val pending = async(Dispatchers.IO) { runCatching { client.operation(server.url("/preview/").toString()) } }
            try {
                assertTrue("the original account request must reach the server", received.await(10, TimeUnit.SECONDS))
                auth.switchAccount()
            } finally { release.countDown() }
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
            assertEquals(heldRequest, server.requestCount)
            repeat(heldRequest) {
                assertNotEquals("Bearer account-B", server.takeRequest().getHeader("Authorization"))
            }
            assertEquals(0, auth.expirations)
            assertEquals("account-B", auth.lease.token)
        }
    }

    @Test fun `an old JSON 401 never retries with the next account token`() = switchBeforeResponse { bots() }
    @Test fun `an old media 401 never retries with the next account token`() = switchBeforeResponse { media("/file") }
    @Test fun `an old multipart 401 never retries with the next account token`() =
        switchBeforeResponse { multipart("/file", "body".toRequestBody()) }
    @Test fun `an old binary 401 never retries with the next account token`() =
        switchBeforeResponse { binaryPost("/file", "body".toRequestBody()) }
    @Test fun `an old preview 401 never retries with the next account token`() = switchBeforeResponse { page ->
        browserResource(page, page).close()
    }
    @Test fun `a late successful response is discarded after switching accounts`() = switchBeforeResponse(200) { bots() }
    @Test fun `a retry 401 cannot sign out the next account`() = switchBeforeResponse(heldRequest = 2) { bots() }

    @Test fun `an API created before an account switch cannot start new authenticated requests`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val auth = Auth()
            val client = api(server, auth)
            auth.switchAccount()
            val operations: List<suspend () -> Any?> = listOf(
                { client.bots() }, { client.media("/file") }, { client.multipart("/file", "body".toRequestBody()) },
                { client.binaryPost("/file", "body".toRequestBody()) }, { client.socketRequest("/ws") },
                { client.streamRequest("/events") }, { client.bearerToken() },
            )
            for (operation in operations) assertTrue(runCatching { operation() }.exceptionOrNull() is CancellationException)
            client.invalidateSession()
            client.streamUnauthorized()
            assertEquals(0, auth.expirations)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `refresh finishing after an account switch cannot supply a retry lease`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            val auth = Auth().apply { refreshAction = { switchAccount(); lease } }
            assertTrue(runCatching { api(server, auth).bots() }.exceptionOrNull() is CancellationException)
            assertEquals(1, server.requestCount)
            assertEquals(0, auth.expirations)
        }
    }

    @Test fun `a refreshed token in the same account is reused by an old 401`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val auth = Auth()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") == "Bearer account-A") {
                        auth.lease = auth.lease.copy(token = "already-refreshed-A")
                        return MockResponse().setResponseCode(401)
                    }
                    return MockResponse().setBody("""{"items":[]}""")
                }
            }
            api(server, auth).bots()
            assertEquals(0, auth.refreshes)
            assertEquals("Bearer account-A", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer already-refreshed-A", server.takeRequest().getHeader("Authorization"))
        }
    }
}
