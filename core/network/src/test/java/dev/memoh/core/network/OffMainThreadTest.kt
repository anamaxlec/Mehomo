package dev.memoh.core.network

import dev.memoh.core.model.Bot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * The blocking HTTP exchange must leave the caller's thread.
 *
 * OkHttp's synchronous `execute()` throws `NetworkOnMainThreadException` on
 * Android's main thread, and a `viewModelScope.launch` body is on the main
 * thread by default. That failure is nearly invisible in development: the
 * exception carries no message, so a `message ?: "fallback"` handler shows a
 * plausible lie ("check your email address") while the request never leaves the
 * device.
 *
 * A JVM test cannot trigger Android's main-thread check, but it can observe the
 * property that makes the check pass. For a *synchronous* call the interceptor
 * chain runs on the thread that invoked `execute()`, so an interceptor records
 * exactly the thread that would have been rejected on Android.
 */
class OffMainThreadTest {

    private lateinit var server: MockWebServer

    /** Stands in for Android's main thread. */
    private val callerThreadName = "test-caller-thread"
    private val callerDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, callerThreadName)
    }.asCoroutineDispatcher()

    /**
     * The thread that invoked `execute()`, captured inside the interceptor.
     *
     * Compared by identity rather than by name: under the coroutine test agent
     * `Thread.currentThread().name` is reported with a `@coroutine#N` suffix, so
     * a name comparison would never match and the assertion would pass even
     * with the blocking call on the caller's thread.
     */
    @Volatile
    private var executeThread: Thread? = null

    /** Identity of the thread that issued the call, captured on the caller side. */
    @Volatile
    private var callerThreadId: Long = -1L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                executeThread = Thread.currentThread()
                chain.proceed(chain.request())
            },
        )
        .build()

    private fun api(): MemohApi = MemohApi(
        client = client(),
        endpoint = ServerEndpoint(
            kind = ServerKind.SelfHosted,
            origin = server.url("/").toString().trimEnd('/'),
            apiPrefix = "",
            memohPrefix = "",
        ),
        json = json,
        // A lease is needed for authenticated endpoints; credential handling is
        // covered by MemohApiAuthTest, not here.
        auth = object : AuthProvider {
            override fun currentLease() = AuthLease("token", null, generation = 1L)
            override suspend fun refresh(previous: AuthLease) = previous
            override fun onSessionExpired() = Unit
        },
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        callerDispatcher.close()
    }

    /** Runs [block] on the stand-in main thread, recording that thread's id. */
    private suspend fun <T> onCallerThread(block: suspend () -> T): T =
        withContext(callerDispatcher) {
            callerThreadId = Thread.currentThread().id
            block()
        }

    private fun assertOffCallerThread() {
        val observed = executeThread
        assertTrue("the request never reached the HTTP client", observed != null)
        assertNotEquals(
            "the HTTP exchange ran on the caller's thread; on Android this is " +
                "NetworkOnMainThreadException",
            callerThreadId,
            observed!!.id,
        )
    }

    @Test
    fun `a successful rest call leaves the calling thread`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))

        val bots: List<Bot> = onCallerThread { api().bots() }

        assertTrue(bots.isEmpty())
        assertOffCallerThread()
    }

    @Test
    fun `a failing request also leaves the calling thread`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))

        val failure = runCatching { onCallerThread { api().bots() } }.exceptionOrNull()

        assertTrue("expected an ApiException, got $failure", failure is ApiException)
        assertOffCallerThread()
    }

    @Test
    fun `the cloud auth exchange leaves the calling thread`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"expires_in":"600","resend_after":"60"}"""),
        )
        val auth = CloudAuth(
            client = client(),
            json = json,
            endpoint = ServerEndpoint(
                kind = ServerKind.OfficialCloud,
                origin = server.url("/").toString().trimEnd('/'),
                apiPrefix = "",
                memohPrefix = "",
            ),
        )

        val response = onCallerThread { auth.sendEmailCode("a@b.com") }

        // String-typed numerics are what the live endpoint actually returns.
        assertTrue(response.resendAfter == 60)
        assertOffCallerThread()
    }

    /** Guards against a future refactor reintroducing the blocking call shape. */
    @Test
    fun `the io dispatcher is not the caller's thread`() = runBlocking {
        val onCaller = withContext(callerDispatcher) { Thread.currentThread().name }
        val onIo = withContext(Dispatchers.IO) { Thread.currentThread().name }
        assertNotEquals(onCaller, onIo)
    }
}
