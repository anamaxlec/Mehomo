package dev.memoh.core.network

import dev.memoh.core.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

class UpstreamApiTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val auth = object : AuthProvider {
        override fun currentLease() = AuthLease("fixture", null, 1)
        override suspend fun refresh(previous: AuthLease) = null
        override fun onSessionExpired() = Unit
    }
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }
    private fun api() = MemohApi(OkHttpClient(), ServerEndpoint(ServerKind.SelfHosted, server.url("/").toString().trimEnd('/'), "", ""), json, auth)
    @Test fun nativeSkillCatalogueUsesTheChatEndpointRatherThanTheManagementList() = runTest {
        server.enqueue(MockResponse().setBody("""{"skills":[{"name":"dataviz","display_name":"图表助手","description":"绘制数据图表","source_kind":"user","state":"ready"}]}"""))
        val skill = api().skillCatalog("bot/1").single()
        val request = server.takeRequest()
        assertEquals("/bots/bot%2F1/skills/catalog", request.requestUrl!!.encodedPath)
        assertEquals("Bearer fixture", request.getHeader("Authorization"))
        assertEquals("dataviz", skill.name)
        assertEquals("图表助手", skill.displayName)
        assertEquals("user", skill.sourceKind)
        server.enqueue(MockResponse().setBody("""{"skills":null}"""))
        assertTrue(api().skillCatalog("b").isEmpty())
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        try { api().skillCatalog("b"); fail("A failed catalogue must not become an empty catalogue") }
        catch (e: ApiException) { assertEquals(503, e.status) }
    }
    @Test fun draftCommandsUseProjectFolderAndDecodeSkillsWithoutBreakingOlderServers() = runTest {
        server.enqueue(MockResponse().setBody("""{"commands":[{"name":"dataviz","kind":"turn","skill":true},{"name":"status","kind":"read"}]}"""))
        val controls = api().agentRuntimeControls("bot/1", "agent/1", "folder+工程")
        val request = server.takeRequest()
        assertEquals("/bots/bot%2F1/agents/agent%2F1/runtime-controls", request.requestUrl!!.encodedPath)
        assertEquals("folder+工程", request.requestUrl!!.queryParameter("workdir_id"))
        assertTrue(controls.commands!![0].skill)
        assertFalse(controls.commands!![1].skill)
        server.enqueue(MockResponse().setBody("""{"commands":[]}"""))
        api().agentRuntimeControls("b", "a")
        assertNull(server.takeRequest().requestUrl!!.queryParameter("workdir_id"))
    }
    @Test fun starterPromptRefreshUsesServerHashLocaleAndCurrentPrompts() = runTest {
        server.enqueue(MockResponse().setBody("""{"hash":"held","items":[{"title":"建议","prompt":"问题"}],"unchanged":false}"""))
        val result = api().starterPrompts("bot/1", "zh-CN", "a+b")
        val read = server.takeRequest()
        assertEquals("/bots/bot%2F1/starter-prompts", read.requestUrl!!.encodedPath)
        assertEquals("a+b", read.requestUrl!!.queryParameter("known_hash"))
        assertEquals("zh-CN", read.requestUrl!!.queryParameter("locale"))
        assertEquals("问题", result.items.single().prompt)
        server.enqueue(MockResponse().setBody("""{"hash":"held","items":[{"title":"另一组","prompt":"另一个问题"}]}"""))
        api().refreshStarterPrompts("bot/1", "zh-CN", listOf("问题"))
        val refresh = server.takeRequest()
        assertEquals("POST", refresh.method)
        assertEquals("/bots/bot%2F1/starter-prompts/refresh", refresh.path)
        val body = json.parseToJsonElement(refresh.body.readUtf8()).jsonObject
        assertEquals("zh-CN", body["locale"]!!.jsonPrimitive.content)
        assertEquals(listOf("问题"), body["avoid"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun scheduleRunCursorAndRecentListFilteringStaySeparate() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[{"log_id":"fire-1","session_id":"s","status":"running","completed_at":null}],"next_cursor":"next+page"}"""))
        val page = api().scheduleRunSessions("b", "schedule/1", "cursor+value", 20)
        val request = server.takeRequest()
        assertEquals("/bots/b/schedule/schedule%2F1/sessions", request.requestUrl!!.encodedPath)
        assertEquals("cursor+value", request.requestUrl!!.queryParameter("cursor"))
        assertEquals("next+page", page.nextCursor)
        assertEquals("running", page.items.single().status)
        assertNull(page.items.single().completedAt)
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        api().sessions("b", hideScheduleRuns = true)
        assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("hide_schedule_runs"))
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        api().sessions("b")
        assertNull(server.takeRequest().requestUrl!!.queryParameter("hide_schedule_runs"))
    }
}
