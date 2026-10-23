package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.*
import dev.memoh.feature.chat.*
import dev.memoh.feature.sessions.*
import dev.memoh.feature.bots.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class UpstreamAdaptationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val json = Json { ignoreUnknownKeys = true }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun repository(server: UpstreamFixture): SessionRepository {
        val base = context
        val prefix = "upstream-${System.nanoTime()}-"
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        val store = CredentialStore(isolated, json)
        store.saveAccount(StoredAccount(prefix, server.origin, "self-hosted", username = "Fixture"))
        store.saveToken(prefix, "fixture-only", null)
        return SessionRepository(store, json, CloudAuth(OkHttpClient(), json))
    }

    @Test fun recommendationPrefillsWithoutSendingAndAsksBeforeReplacingADraft() {
        var state by mutableStateOf(ChatUiState(session = Session("s"), historyLoading = false, modelCatalogLoading = false, awaitingSnapshot = false, starterPrompts = StarterPromptsResponse("material",
            listOf(StarterPrompt("计划本周", "先问我本周的安排")))))
        val sends = AtomicInteger()
        compose.setContent { MemohTheme { ChatScreen(state, {}, { sends.incrementAndGet() }, {},
            { state = state.copy(draft = it) }, {}, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> }, { _, _, _ -> }, { _, _ -> }, {}) } }
        compose.onNodeWithText("计划本周").performClick()
        compose.waitUntil { state.draft == "先问我本周的安排" }
        assertEquals(0, sends.get())
        compose.runOnIdle { state = state.copy(draft = "保留我的草稿") }
        compose.onNodeWithText("计划本周").performClick()
        compose.onNodeWithText("替换当前草稿").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals("保留我的草稿", state.draft)
        compose.onNodeWithText("计划本周").performClick()
        compose.onNodeWithText("替换", substring = false).performClick()
        assertEquals("先问我本周的安排", state.draft)
        assertEquals(0, sends.get())
    }

    @Test fun examplesRouteKnownMissingCapabilitiesToSettings() {
        var configured: String? = null
        compose.setContent { MemohTheme { ConversationSuggestions(ChatUiState(session = Session("s"),
            exampleCapabilities = mapOf("web_search" to false)), {}, {}, { configured = it }) } }
        compose.onNodeWithText("更多示例").performClick()
        compose.onNodeWithText("研究", substring = false).performClick()
        compose.onNode(hasText("研究助理") and hasText("先配置搜索服务")).performScrollTo().performClick()
        compose.waitUntil { configured == "web_search" }
    }

    @Test fun promptSetsPersistAndRemainSeparateForAccountsTeamsAndBots() = runBlocking {
        val settings = SettingsStore(context)
        val id = "test-${System.nanoTime()}"
        val held = StarterPromptsResponse("server-material", listOf(StarterPrompt("标题", "问题")))
        settings.saveStarterPrompts(id, "team", "bot", "zh-CN", held)
        assertEquals(held, SettingsStore(context).starterPrompts(id, "team", "bot", "zh-CN"))
        assertNull(settings.starterPrompts("other-$id", "team", "bot", "zh-CN"))
        assertNull(settings.starterPrompts(id, "other-team", "bot", "zh-CN"))
        assertNull(settings.starterPrompts(id, "team", "other-bot", "zh-CN"))
        assertNull(settings.starterPrompts(id, "team", "bot", "en"))
    }

    @Test fun batchDeleteKeepsFailedSelectionsAndDoesNotRestoreSuccessfulRows() {
        var rejectSecond = true
        val deleted = mutableSetOf<String>()
        UpstreamFixture { request -> when {
            request.path == "/bots" -> UpstreamReply(body = """{"items":[{"id":"b","name":"Fixture","status":"ready","current_user_permissions":["chat","manage"]}]}""")
            request.path == "/bots/b/workdirs" -> UpstreamReply(body = """{"workdirs":[]}""")
            request.path == "/bots/b/sessions" -> UpstreamReply(body = buildJsonObject { put("items", JsonArray(listOf("a", "b").filterNot(deleted::contains).map { apiBody("id" to it, "title" to "会话 $it") })) }.toString())
            request.method == "DELETE" -> {
                val id = request.path.substringAfterLast('/')
                if (id == "b" && rejectSecond) UpstreamReply(503, """{"code":"server.unavailable"}""")
                else { deleted += id; UpstreamReply(204, "") }
            }
            else -> UpstreamReply()
        } }.use { server ->
            val vm = SessionsViewModel(repository(server), SettingsStore(context))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); SessionsScreen(state, vm::selectBot, {}, vm::rename,
                vm::beginRename, vm::cancelRename, vm::beginDelete, vm::cancelDelete, vm::delete,
                onBeginSelection = vm::beginSelection, onToggleSelection = vm::toggleSelection, onSelectVisible = vm::selectVisible,
                onCancelSelection = vm::cancelSelection, onDeleteSelected = vm::deleteSelected, onDismissError = vm::dismissError, onRetry = vm::refresh) } }
            try {
                compose.runOnIdle { vm.start() }
                compose.waitUntil(8000) { vm.state.value.sessions.size == 2 }
                assertEquals("true", server.requests.first { it.path == "/bots/b/sessions" }.query("hide_schedule_runs"))
                compose.runOnIdle { vm.beginSelection(vm.state.value.sessions.first()) }
                compose.onNodeWithText("全选当前列表").performClick()
                compose.onNodeWithText("已选 2 个").assertExists()
                compose.onNodeWithContentDescription("删除所选会话").performClick()
                compose.onNodeWithText("删除 2 个会话").assertExists()
                compose.onNodeWithText("删除", substring = false).performClick()
                compose.waitUntil(8000) { !vm.state.value.deletingSelected && vm.state.value.error != null }
                assertEquals(listOf("b"), vm.state.value.sessions.map { it.id })
                assertEquals(setOf("b"), vm.state.value.selectedSessions)
                assertTrue(vm.state.value.selecting)
                rejectSecond = false
                compose.runOnIdle { vm.deleteSelected() }
                compose.waitUntil(8000) { !vm.state.value.deletingSelected && vm.state.value.sessions.isEmpty() }
                assertFalse(vm.state.value.selecting)
                assertEquals(1, server.requests.count { it.method == "DELETE" && it.path.endsWith("/a") })
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun sessionSearchClosesAcrossTabsAndBackKeepsTheConversationList() {
        val bot = Bot("b", "Fixture", currentUserPermissions = listOf("chat", "manage"))
        var state by mutableStateOf(SessionsUiState(initialized = true, bot = bot, bots = listOf(bot),
            sessions = listOf(Session("s", title = "会话 A"))))
        compose.setContent { MemohTheme {
            SessionsScreen(state, onSelectBot = {}, onOpenSession = {}, onRename = { _, _ -> },
                onBeginRename = {}, onCancelRename = {}, onBeginDelete = {}, onCancelDelete = {},
                onConfirmDelete = {}, onDismissError = {}, onRetry = {},
                onQueryChange = { state = state.copy(query = it) }, onSelectTab = { state = state.copy(tab = it) },
                scheduleContent = { Text("日程列表") })
        } }
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("会话")
        compose.waitUntil { state.query == "会话" }
        compose.onNodeWithText("日程", substring = false).performClick()
        compose.waitUntil { state.tab == BotTab.Schedules && state.query.isEmpty() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("日程列表").assertExists()
        compose.onNodeWithText("Fixture").assertExists()
        compose.onNodeWithText("对话", substring = false).performClick()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("会话")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { state.query.isEmpty() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("会话 A").assertExists()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun pausedResourcesAndConnectorDiscoveryFailuresUseTheNewContract() {
        var paused = true
        var failed = true
        UpstreamFixture { request -> when (request.path) {
            "/bots/b" -> UpstreamReply(body = """{"id":"b","name":"Fixture","current_user_permissions":["manage","chat"]}""")
            "/bots/b/container" -> UpstreamReply(body = """{"container_id":"c","status":"${if (paused) "paused" else "running"}","task_running":${!paused}}""")
            "/bots/b/container/metrics" -> UpstreamReply(body = """{"supported":true,"metrics":{"cpu":{"cores":4,"usage_percent":0},"memory":{"limit_bytes":4294967296,"usage_bytes":0},"storage":{"limit_bytes":21474836480}}}""")
            "/bots/b/connectors" -> UpstreamReply(body = if (failed) """{"items":[{"connection_id":"github","alias":"GitHub","status":"connected","enabled":true,"tool_discovery_error":"工具会话已过期"}],"tool_discovery":{"error":"服务未响应"}}""" else """{"items":[{"connection_id":"github","alias":"GitHub","status":"connected","enabled":true}]}""")
            "/bots/b/apps", "/connectors/catalog" -> UpstreamReply(body = """{"items":[]}""")
            "/ping" -> UpstreamReply(body = """{"snapshot_supported":false}""")
            else -> UpstreamReply()
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
            try {
                compose.runOnIdle { vm.open("b", ManagementPage.Workspace) }
                compose.waitUntil(8000) { vm.state.value.loaded }
                compose.onNodeWithText("已暂停").assertExists()
                compose.onNodeWithText("CPU · 4 核").assertExists()
                compose.onNodeWithText("存储 · 20.0 GiB").performScrollTo().assertExists()
                paused = false
                runBlocking { vm.refreshWorkspaceState() }
                compose.waitUntil(8000) { vm.state.value.data["container"]?.jsonObject?.get("status")?.jsonPrimitive?.content == "running" }
                compose.runOnIdle { vm.open("b", ManagementPage.Connectors) }
                compose.waitUntil(8000) { vm.state.value.loaded && vm.state.value.page == ManagementPage.Connectors }
                compose.onNodeWithText("连接器工具加载失败：服务未响应").assertExists()
                compose.onNodeWithText("工具加载失败：工具会话已过期", substring = true).assertExists()
                assertEquals("connected", vm.state.value.data["connections"]!!.jsonObject["items"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
                failed = false
                compose.runOnIdle { vm.refresh() }
                compose.waitUntil(8000) { !vm.state.value.loading && vm.state.value.data["connections"]?.jsonObject?.containsKey("tool_discovery") == false }
                compose.onNodeWithText("工具加载失败：工具会话已过期", substring = true).assertDoesNotExist()
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun appDemoCreatesAnEditableConversationWithoutStartingARun() {
        var created: Session? = null
        var prompt: String? = null
        UpstreamFixture { request -> when (request.path) {
            "/bots/b/apps" -> UpstreamReply(body = """{"workspace_state":"running","items":[{"app_id":"research","name":"研究工具包","status":"discovered"}]}""")
            "/bots/b/sessions" -> UpstreamReply(201, """{"id":"demo","bot_id":"b","channel_type":"local"}""")
            else -> UpstreamReply()
        } }.use { server ->
            val vm = BotFeatureViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); BotFeatureScreen(state, vm,
                onTryApp = { name -> vm.startAppDemo(name) { session, text -> created = session; prompt = text } }) } }
            try {
                compose.runOnIdle { vm.open("b", BotFeature.Apps) }
                compose.waitUntil(8000) { vm.state.value.hasLoaded }
                compose.onNodeWithText("试一试").performScrollTo().performClick()
                compose.waitUntil(8000) { created?.id == "demo" }
                assertTrue(prompt!!.contains("研究工具包"))
                assertEquals(listOf("/bots/b/sessions"), server.requests.filter { it.method == "POST" }.map { it.path })
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun scheduleRunPaginationKeepsRowsOnFailureAndRefreshesRunningStatus() {
        var failNext = false
        var completed = false
        UpstreamFixture { request -> when (request.path) {
            "/bots/b" -> UpstreamReply(body = """{"id":"b","name":"Fixture","current_user_permissions":["manage","chat"]}""")
            "/bots/b/schedule" -> UpstreamReply(body = """{"items":[{"id":"daily","name":"每日简报","pattern":"0 8 * * *","enabled":true}]}""")
            "/bots/b/schedule/daily/sessions" -> if (request.query("cursor") == "older") {
                if (failNext) UpstreamReply(503, """{"code":"server.unavailable"}""")
                else UpstreamReply(body = """{"items":[{"log_id":"2","session_id":"old","title":"上一次","status":"ok"}]}""")
            } else UpstreamReply(body = """{"items":[{"log_id":"1","session_id":"current","title":"今日简报","status":"${if (completed) "ok" else "running"}"}],"next_cursor":"older"}""")
            else -> UpstreamReply()
        } }.use { server ->
            val vm = BotFeatureViewModel(repository(server))
            try {
                compose.runOnIdle { vm.open("b", BotFeature.Schedules) }
                compose.waitUntil(8000) { vm.state.value.hasLoaded }
                compose.runOnIdle { vm.loadScheduleRuns("daily") }
                compose.waitUntil(8000) { vm.state.value.scheduleRuns["daily"]?.loaded == true }
                assertEquals("running", vm.state.value.scheduleRuns["daily"]!!.items.single().status)
                failNext = true
                compose.runOnIdle { vm.loadScheduleRuns("daily", more = true) }
                compose.waitUntil(8000) { vm.state.value.scheduleRuns["daily"]?.error != null }
                assertEquals(1, vm.state.value.scheduleRuns["daily"]!!.items.size)
                failNext = false
                compose.runOnIdle { vm.loadScheduleRuns("daily", more = true) }
                compose.waitUntil(8000) { vm.state.value.scheduleRuns["daily"]?.items?.size == 2 }
                completed = true
                compose.runOnIdle { vm.loadScheduleRuns("daily") }
                compose.waitUntil(8000) { vm.state.value.scheduleRuns["daily"]?.items?.firstOrNull()?.status == "ok" }
                assertEquals(listOf("1", "2"), vm.state.value.scheduleRuns["daily"]!!.items.map { it.logId })
            } finally { vm.viewModelScope.cancel() }
        }
    }
}

internal data class UpstreamReply(val status: Int = 200, val body: String = "{}")
internal data class UpstreamRequest(val method: String, val target: String, val body: String) {
    val path get() = target.substringBefore('?')
    fun query(key: String): String? = target.substringAfter('?', "").split('&').firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
}
internal class UpstreamFixture(private val respond: (UpstreamRequest) -> UpstreamReply) : AutoCloseable {
    private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val origin = "http://localhost:${socket.localPort}"
    val requests = CopyOnWriteArrayList<UpstreamRequest>()
    private val worker = Thread {
        while (!socket.isClosed) try { socket.accept().use { client ->
            client.soTimeout = 8000
            val input = client.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) { val next = input.read(); if (next < 0) break; header.append(next.toChar()) }
            val lines = header.toString().split("\r\n")
            val first = lines.first().split(' ')
            val size = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) { val count = input.read(bytes, read, size - read); if (count < 0) break; read += count }
            val request = UpstreamRequest(first[0], first.getOrElse(1) { "/" }, String(bytes, Charsets.UTF_8))
            requests += request
            val reply = respond(request)
            val body = reply.body.toByteArray()
            val status = "HTTP/1.1 ${reply.status} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().apply { write(status.toByteArray()); write(body); flush() }
        } } catch (_: Exception) { if (socket.isClosed) break }
    }.apply { isDaemon = true; start() }
    override fun close() { socket.close(); worker.join(1000) }
}
