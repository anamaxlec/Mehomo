package dev.memoh.core.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BotApiIntegrationTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val auth = object : AuthProvider {
        override fun currentLease() = AuthLease("test-token", null, 1)
        override suspend fun refresh(previous: AuthLease) = null
        override fun onSessionExpired() = Unit
    }
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }
    private fun api() = MemohApi(OkHttpClient(), ServerEndpoint(ServerKind.SelfHosted, server.url("/").toString().trimEnd('/'), "", ""), json, auth)
    private fun response(body: String = "{}", status: Int = 200) { server.enqueue(MockResponse().setResponseCode(status).setBody(body)) }
    private fun body() = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun `session pagination round trips opaque cursors without changing plus or separators`() = runTest {
        val cursor = "older+page/with=padding&marker"
        response("""{"items":[{"id":"recent","title":"Recent"}],"next_cursor":"$cursor"}""")
        val first = api().sessions("bot")
        assertEquals(cursor, first.nextCursor)
        server.takeRequest()
        response("""{"items":[{"id":"older","title":"Older"}]}""")
        assertEquals("older", api().sessions("bot", cursor = first.nextCursor).items.single().id)
        assertEquals(cursor, server.takeRequest().requestUrl!!.queryParameter("cursor"))
    }

    @Test fun `usage summary and paginated records include the same exclusive date range`() = runTest {
        response("""{"chat":[{"day":"2026-09-30","input_tokens":120}],"by_model":[]}""")
        assertEquals(120L, api().tokenUsage("bot", "2026-09-01", "2026-10-01").chat!!.single().inputTokens)
        val summary = server.takeRequest().requestUrl!!
        assertEquals("2026-09-01", summary.queryParameter("from"))
        assertEquals("2026-10-01", summary.queryParameter("to"))
        response("""{"items":[{"id":"record","input_tokens":120}],"total":51}""")
        assertEquals(51L, api().tokenRecords("bot", "2026-09-01", "2026-10-01", 50).total)
        val records = server.takeRequest().requestUrl!!
        assertEquals(summary.queryParameter("from"), records.queryParameter("from"))
        assertEquals(summary.queryParameter("to"), records.queryParameter("to"))
        assertEquals("50", records.queryParameter("offset"))
    }

    @Test fun `workspace paths and existing file revisions retain the exact server fields`() = runTest {
        response("""{"path":"/data/个人项目","entries":[{"name":"note.md","path":"/data/个人项目/note.md","size":12,"modTime":"today","isDir":false}]}""")
        val listing = api().files("bot", "/data/个人项目")
        assertEquals("note.md", listing.entries.single().name)
        assertFalse(listing.entries.single().isDir)
        assertEquals("/data/个人项目", server.takeRequest().requestUrl!!.queryParameter("path"))
        response("""{"path":"/data/note.md","content":"Old","size":3,"revision":"server-version"}""")
        val document = api().readFile("bot", "/data/note.md")
        server.takeRequest()
        response("file changed on disk", 409)
        try { api().writeFile("bot", document.path, "Draft", document.revision); fail("Expected a revision conflict") }
        catch (e: ApiException) { assertEquals(409, e.status) }
        val write = body()
        assertEquals("server-version", write["expectedRevision"]!!.jsonPrimitive.content)
        assertEquals("Draft", write["content"]!!.jsonPrimitive.content)
        assertFalse(write.containsKey("expected_revision"))
        response("""{"ok":true,"revision":"new-version"}""")
        api().writeFile("bot", "/data/new.md", "New")
        assertFalse(body().containsKey("expectedRevision"))
    }
    @Test fun `workspace uploads are multipart with an absolute destination and account auth`() = runTest {
        response("""{"path":"/data/example file.txt","size":5}""")
        assertEquals(5L, api().uploadFile("bot", "/data/example file.txt", "example file.txt", "text/plain", "hello".toByteArray()).size)
        val request = server.takeRequest()
        assertEquals("/bots/bot/container/fs/upload", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data;"))
        val upload = request.body.readUtf8()
        assertTrue(upload.contains("name=\"path\""))
        assertTrue(upload.contains("/data/example file.txt"))
        assertTrue(upload.contains("filename=\"example file.txt\""))
        assertTrue(upload.contains("hello"))
        response("", 204); api().renameFile("bot", "/data/old", "/data/new")
        assertEquals("/data/old", body()["oldPath"]!!.jsonPrimitive.content)
        response("", 204); api().deleteFile("bot", "/data/folder", false)
        assertFalse(body()["recursive"]!!.jsonPrimitive.boolean)
    }

    @Test fun `session status uses model override and the complete budget basis`() = runTest {
        response("""{"message_count":20,"context_usage":{"used_tokens":800,"context_window":4000,"breakdown":[{"kind":"conversation_event","token_estimate":1000}],"tool_defs":[{"provider":"mcp","tools":2,"token_estimate":200}],"budget_plan":{"window":3000,"output_reserve":500},"compaction":{"enabled":true,"auto_tokens":2400}},"cache_stats":{"cache_hit_rate":0.5,"cache_read_tokens":400},"skills":["search"]}""")
        val info = api().sessionInfo("bot", "session", "model + alias")
        assertEquals(20L, info.messageCount)
        assertEquals(1200L, info.contextUsage.tokens)
        assertEquals(3000L, info.contextUsage.window(9000))
        assertEquals(.4f, info.contextUsage.fraction()!!, .0001f)
        assertEquals(2400L, info.contextUsage.autoCompactTokens)
        assertEquals(listOf("search"), info.skills)
        val request = server.takeRequest()
        assertEquals("/bots/bot/sessions/session/status", request.requestUrl!!.encodedPath)
        assertEquals("model + alias", request.requestUrl!!.queryParameter("model_id"))
        val unknown = dev.memoh.core.model.ContextUsage(usedTokens = 500)
        assertNull(unknown.fraction())
        assertEquals(.25f, unknown.fraction(2000)!!, .0001f)
        assertNull(dev.memoh.core.model.ContextUsage(compaction = dev.memoh.core.model.ContextCompaction(true, 100)).autoCompactTokens)
    }

    @Test fun `memory and schedule send actual server fields`() = runTest {
        response("""{"results":[{"id":"m1","memory":"Keep this"}]}""")
        assertEquals("Keep this", api().addMemory("bot", "Keep this").results!!.single().memory)
        val memory = body()
        assertEquals("Keep this", memory["message"]!!.jsonPrimitive.content)
        assertFalse(memory.containsKey("memory"))
        response("""{"id":"schedule","command":"Summarize","pattern":"0 9 * * *"}""")
        assertEquals("Summarize", api().saveSchedule("bot", null, apiBody("name" to "Daily", "command" to "Summarize", "pattern" to "0 9 * * *", "max_calls" to JsonNull)).command)
        val schedule = body()
        assertEquals("Summarize", schedule["command"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, schedule["max_calls"])
    }
    @Test fun `empty deletion and goal responses are accepted`() = runTest {
        response("", 204); api().deleteMemory("bot", "m1")
        assertEquals("DELETE", server.takeRequest().method)
        response("", 204); api().controlRuntimeGoal("bot", "session", "pause")
        assertEquals("pause", body()["action"]!!.jsonPrimitive.content)
    }
    @Test fun `history pagination uses the persisted UUID rather than a block number`() = runTest {
        val cursor = "d8cdc08d-633f-4474-b959-f98da81de992"
        response("""{"items":[{"id":"$cursor","turn_id":"turn","role":"assistant","messages":[{"id":1,"type":"tool","elapsed_time_seconds":0.125}]}]}""")
        assertEquals(0.125, api().messages("bot", "session", beforeMessageId = cursor).items.single().safeMessages.single().elapsedTimeSeconds)
        assertEquals(cursor, server.takeRequest().requestUrl!!.queryParameter("before_message_id"))
    }
    @Test fun `provider catalog and external model catalog decode their different response shapes`() = runTest {
        response("""[{"id":"provider","name":"DeepSeek","config":{"unknown":true}}]""")
        assertEquals("DeepSeek", api().providers().single().name)
        assertEquals("/providers", server.takeRequest().path)
        response("""{"configured_model_id":"alias","models":[{"id":"alias","name":"Model"}]}""")
        assertEquals("alias", api().agentModels("bot", "agent").pickerModels.single().id)
        assertEquals("/bots/bot/agents/agent/models", server.takeRequest().path)
    }
    @Test fun `queue reordering uses typed references and plan changes stay independent`() = runTest {
        response("", 204); api().reorderQueue("bot", "session", "second", "first", true)
        val queue = body()
        assertEquals("second", queue["item"]!!.jsonObject["item_id"]!!.jsonPrimitive.content)
        assertEquals("first", queue["before"]!!.jsonObject["item_id"]!!.jsonPrimitive.content)
        assertFalse(queue.containsKey("item_ids"))
        response(); api().setRuntimeMode("bot", "session", "plan", "plan")
        assertEquals("plan", body()["mode_kind"]!!.jsonPrimitive.content)
    }
    @Test fun `market pagination and query escaping decode data rather than items`() = runTest {
        response("""{"data":[{"app_id":"a","name":"Tool"}],"page":2,"total":45}""")
        assertEquals("Tool", api().marketApps(2, "a & b").data!!.single().name)
        val request = server.takeRequest()
        assertEquals("a & b", request.requestUrl!!.queryParameter("q"))
        assertEquals("2", request.requestUrl!!.queryParameter("page"))
    }
    @Test fun `SSE preserves more than one buffer of install frames and multiline data`() = runTest {
        val payload = (1..100).joinToString("") { "data: {\"type\":\"log\",\"data\":\"$it\"}\n\n" } + "data: {\"type\":\"done\",\n" + "data: \"status\":\"installed\"}\n\n"
        response(payload)
        val events = api().installApp("bot", "registry", "app", "server-revision").toList()
        assertEquals(101, events.size)
        assertEquals("installed", events.last().status)
        assertEquals("server-revision", body()["revision"]!!.jsonPrimitive.content)
    }
    @Test fun `SSE application errors remain visible even with HTTP 200`() = runTest {
        response("data: {\"type\":\"error\",\"message\":\"Missing dependency\"}\n\n")
        val error = api().resumeApp("bot", "installation").toList().single()
        assertEquals("error", error.type)
        assertEquals("Missing dependency", error.message)
    }
    @Test fun `app updates use registry and release selection`() = runTest {
        response("data: {\"type\":\"done\",\"status\":\"installed\"}\n\n")
        api().updateApp("bot", "registry", "app").toList()
        val update = body()
        assertEquals("registry", update["registry_id"]!!.jsonPrimitive.content)
        assertTrue(update["release"]!!.jsonPrimitive.boolean)
        assertFalse(update.containsKey("installation_ids"))
    }
    @Test fun `Cloud REST restores cookies and shares team headers with fresh ticket handshakes`() = runTest {
        val endpoint = ServerEndpoint(ServerKind.OfficialCloud, server.url("/").toString().trimEnd('/'), "/api/v1", "/api/memoh")
        val storage = object : CloudCookieStorage {
            var cookies = listOf(Cookie.parse(server.url("/"), "session=restored; Path=/; HttpOnly; Max-Age=3600")!!)
            override fun loadCloudCookies() = cookies
            override fun saveCloudCookies(cookies: List<Cookie>) { this.cookies = cookies }
        }
        val cloud = CloudAuth(OkHttpClient(), json, endpoint, storage).apply { teamId = "team with space" }
        val api = MemohApi(OkHttpClient(), endpoint, json, cloudAuth = cloud)
        response("""{"items":[]}""")
        api.bots()
        val request = server.takeRequest()
        assertEquals("/api/memoh/bots", request.path)
        assertEquals("session=restored", request.getHeader("Cookie"))
        assertEquals("team with space", request.getHeader("X-Team-Id"))
        assertNull(request.getHeader("Authorization"))
        response("""{"ticket":"one","expires_at":"later"}"""); response("""{"ticket":"two","expires_at":"later"}""")
        val first = cloud.webSocketUrl("bot")
        val second = cloud.webSocketUrl("bot")
        assertTrue(first.contains("ticket=one")); assertTrue(second.contains("ticket=two"))
        repeat(2) { assertEquals("/api/v1/ws-tickets", server.takeRequest().path) }
        cloud.clearSession()
        assertFalse(cloud.hasSession); assertTrue(storage.cookies.isEmpty())
    }
    @Test fun `Cloud MFA accepts a cookie-only empty success`() = runTest {
        val endpoint = ServerEndpoint(ServerKind.OfficialCloud, server.url("/").toString().trimEnd('/'), "/api/v1", "/api/memoh")
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Set-Cookie", "session=verified; Path=/; HttpOnly"))
        val cloud = CloudAuth(OkHttpClient(), json, endpoint)
        cloud.verifyMfa("mfa-token", "123456")
        assertTrue(cloud.hasSession)
        assertEquals("123456", body()["totp_code"]!!.jsonPrimitive.content)
    }
}
