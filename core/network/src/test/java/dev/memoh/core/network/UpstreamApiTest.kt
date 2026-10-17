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
