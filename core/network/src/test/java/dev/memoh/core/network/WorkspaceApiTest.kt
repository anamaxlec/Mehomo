package dev.memoh.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WorkspaceApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val auth = object : AuthProvider {
        override fun currentLease() = AuthLease("test-token", null, 1)
        override suspend fun refresh(previous: AuthLease) = null
        override fun onSessionExpired() = Unit
    }
    private fun api(server: MockWebServer) = MemohApi(OkHttpClient(), ServerEndpoint(ServerKind.SelfHosted,
        server.url("/").toString().trimEnd('/'), "", ""), json, auth)

    @Test fun `browser shorthand IPv6 and query fragments resolve without changing the port`() {
        assertEquals(BrowserAddress(5173, "/"), parseBrowserAddress(""))
        assertEquals(BrowserAddress(80, "/path?a=1#top"), parseBrowserAddress("localhost:80/path?a=1#top"))
        assertEquals(BrowserAddress(4000, "/hello%20world"), parseBrowserAddress(":4000/hello%20world"))
        assertEquals(BrowserAddress(443, "/"), parseBrowserAddress("https://[::1]:443"))
        assertEquals(BrowserAddress(3000, "/"), parseBrowserAddress("3000"))
        for (value in listOf("example.com:4000", "localhost", "localhost:0", "localhost:65536", "user@localhost:123", "javascript:alert(1)")) {
            try { parseBrowserAddress(value); fail("Expected rejection of $value") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `browser previews and desktop offers use typed workspace endpoints`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            val api = api(server)
            server.enqueue(MockResponse().setBody("""{"id":"viewer","url":"https://preview.example/","expires_at":"later"}"""))
            assertEquals("viewer", api.createBrowserSession("bot", 5173, "/hello?q=test").id)
            val browser = server.takeRequest()
            assertEquals("/bots/bot/container/browser/sessions", browser.path)
            assertEquals(5173, json.parseToJsonElement(browser.body.readUtf8()).jsonObject["port"]!!.jsonPrimitive.int)
            server.enqueue(MockResponse().setBody("""{"type":"answer","sdp":"answer-sdp","session_id":"display-viewer"}"""))
            assertEquals("display-viewer", api.displayAnswer("bot", "offer-sdp\r\n").sessionId)
            val offer = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("offer-sdp\r\n", offer["sdp"]!!.jsonPrimitive.content)
            assertEquals("localhost", offer["candidate_host"]!!.jsonPrimitive.content)
            server.enqueue(MockResponse().setResponseCode(204))
            api.closeDisplaySession("bot", "display-viewer")
            val close = server.takeRequest()
            assertEquals("DELETE", close.method)
            assertEquals("/bots/bot/container/display/sessions/display-viewer", close.path)
        } finally { server.shutdown() }
    }
    @Test fun `terminal exchanges raw binary UTF8 and JSON resize with account headers`() = runBlocking {
        val server = MockWebServer()
        val incoming = LinkedBlockingQueue<ByteString>()
        val controls = LinkedBlockingQueue<String>()
        val output = LinkedBlockingQueue<ByteArray>()
        val connected = LinkedBlockingQueue<Boolean>()
        val bytes = "你好\u001b[31m red\u001b[0m".toByteArray()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(bytes.copyOfRange(0, 2).toByteString())
                webSocket.send(bytes.copyOfRange(2, bytes.size).toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { incoming.add(bytes) }
            override fun onMessage(webSocket: WebSocket, text: String) { controls.add(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        server.start()
        val terminal = TerminalConnection(api(server), "bot", { connected.add(true) }, { output.add(it) }, {})
        try {
            terminal.connect(120, 30)
            assertEquals(true, connected.poll(5, TimeUnit.SECONDS))
            val request = server.takeRequest()
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals("120", request.requestUrl!!.queryParameter("cols"))
            assertArrayEquals(bytes, output.poll(5, TimeUnit.SECONDS)!! + output.poll(5, TimeUnit.SECONDS)!!)
            assertTrue(terminal.input("pwd\r".toByteArray()))
            assertEquals("pwd\r", incoming.poll(5, TimeUnit.SECONDS)!!.utf8())
            assertTrue(terminal.resize(90, 40))
            val resize = json.parseToJsonElement(controls.poll(5, TimeUnit.SECONDS)!!).jsonObject
            assertEquals("resize", resize["type"]!!.jsonPrimitive.content)
            assertEquals(90, resize["cols"]!!.jsonPrimitive.int)
            terminal.close()
            assertFalse(terminal.input("must not be replayed".toByteArray()))
        } finally { terminal.close(); server.shutdown() }
    }
    @Test fun `Cloud terminal handshakes obtain a fresh ticket and preserve geometry`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            val endpoint = ServerEndpoint(ServerKind.OfficialCloud, server.url("/").toString().trimEnd('/'), "/api/v1", "/api/memoh")
            val cloud = CloudAuth(OkHttpClient(), json, endpoint, object : CloudCookieStorage {
                override fun loadCloudCookies() = listOf(Cookie.parse(server.url("/"), "session=unit-test; Path=/; Max-Age=3600")!!)
                override fun saveCloudCookies(cookies: List<Cookie>) = Unit
            }).apply { teamId = "team" }
            val api = MemohApi(OkHttpClient(), endpoint, json, cloudAuth = cloud)
            for (ticket in listOf("first", "second")) {
                server.enqueue(MockResponse().setBody("""{"ticket":"$ticket"}"""))
                val request = api.socketRequest("/bots/bot/container/terminal/ws?cols=90&rows=40")
                assertEquals(ticket, request.url.queryParameter("ticket"))
                assertEquals("90", request.url.queryParameter("cols"))
                assertEquals("team", request.header("X-Team-Id"))
                assertEquals("/api/v1/ws-tickets", server.takeRequest().path)
            }
        } finally { server.shutdown() }
    }
    @Test fun `Cloud display uses runtime gateway and native subprotocol rather than a WebRTC offer`() = runBlocking {
        val server = MockWebServer(); server.start()
        val incoming = LinkedBlockingQueue<ByteString>()
        val output = LinkedBlockingQueue<ByteArray>()
        val opened = LinkedBlockingQueue<Boolean>()
        try {
            val endpoint = ServerEndpoint(ServerKind.OfficialCloud, server.url("/").toString().trimEnd('/'), "/api/v1", "/api/memoh")
            val cloud = CloudAuth(OkHttpClient(), json, endpoint, object : CloudCookieStorage {
                override fun loadCloudCookies() = listOf(Cookie.parse(server.url("/"), "session=unit-test; Path=/; Max-Age=3600")!!)
                override fun saveCloudCookies(cookies: List<Cookie>) = Unit
            }).apply { teamId = "team" }
            val api = MemohApi(OkHttpClient(), endpoint, json, cloudAuth = cloud)
            assertTrue(api.usesRuntimeDisplay)
            server.enqueue(MockResponse().setBody("""{"session_id":"viewer","token":"runtime-only-token"}"""))
            server.enqueue(MockResponse().setBody("""{"ticket":"one-shot"}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("RFB 003.008\n".toByteArray().toByteString()) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { incoming.add(bytes) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }))
            val connection = RuntimeDisplayConnection(api, "bot", { opened.add(true) }, { output.add(it) }, {})
            try {
                connection.connect()
                assertEquals(true, opened.poll(5, TimeUnit.SECONDS))
                assertEquals("/api/memoh/bots/bot/container/display/runtime-session", server.takeRequest().path)
                assertEquals("/api/v1/ws-tickets", server.takeRequest().path)
                val socket = server.takeRequest()
                assertEquals("/api/runtime-gateway/v1/display/viewer", socket.requestUrl!!.encodedPath)
                assertEquals("one-shot", socket.requestUrl!!.queryParameter("ticket"))
                assertEquals("team", socket.getHeader("X-Team-Id"))
                assertEquals("memoh-runtime-token." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("runtime-only-token".toByteArray()), socket.getHeader("Sec-WebSocket-Protocol"))
                assertEquals("RFB 003.008\n", output.poll(5, TimeUnit.SECONDS)!!.toString(Charsets.UTF_8))
                assertTrue(connection.input("RFB 003.008\n".toByteArray()))
                assertEquals("RFB 003.008\n", incoming.poll(5, TimeUnit.SECONDS)!!.utf8())
                connection.close()
                assertFalse(connection.input(byteArrayOf(1)))
            } finally { connection.close() }
        } finally { server.shutdown() }
    }

}
