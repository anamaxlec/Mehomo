package dev.memoh.android

import android.content.ContextWrapper
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.*
import dev.memoh.feature.bots.*
import dev.memoh.feature.chat.ChatUiState
import dev.memoh.feature.chat.components.ContextInspectorSheet
import dev.memoh.feature.sessions.*
import dev.memoh.core.markdown.*
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Offline fixtures only. No test reads a user's account or contacts Cloud. */
@RunWith(AndroidJUnit4::class)
class RecentUpdateIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val json = Json { ignoreUnknownKeys = true }
    private fun repository(server: FixtureServer): SessionRepository {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "recent-fixture-${System.nanoTime()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        val credentials = CredentialStore(context, json)
        credentials.saveAccount(StoredAccount("fixture", server.origin, "self-hosted", username = "Fixture"))
        credentials.saveToken("fixture", "fixture-token", null)
        return SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); Thread.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun removalWaitsFor404AndOffersWithdrawalAfterFailure() {
        val deletions = AtomicInteger()
        val removalReads = AtomicInteger()
        var removal = ""
        FixtureServer { request ->
            when (request.path) {
                "/bots/b" -> Reply(body = """{"id":"b","name":"Fixture","current_user_permissions":["manage","chat"]}""")
                "/bots/b/container" -> when (request.method) {
                    "DELETE" -> { deletions.incrementAndGet(); removalReads.set(0); removal = "removing"; Reply(202, """{"status":"accepted"}""") }
                    else -> {
                        if (removal == "removing") {
                            if (deletions.get() == 1) removal = "failed"
                            else if (removalReads.incrementAndGet() >= 2) removal = "gone"
                        }
                        if (removal == "gone") Reply(404, """{"code":"workspace.not_found"}""")
                        else Reply(body = buildJsonObject {
                            put("container_id", "c"); put("task_running", false)
                            if (removal.isNotBlank()) put("removal", apiBody("state" to removal, "preserve_data" to true))
                        }.toString())
                    }
                }
                "/bots/b/container/removal/cancel" -> { removal = ""; Reply(204, "") }
                "/ping" -> Reply(body = """{"snapshot_supported":false}""")
                else -> Reply(body = "{}")
            }
        }.use { server ->
            val repository = repository(server)
            val vm = ManagementViewModel(repository)
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
            try {
                compose.runOnIdle { vm.open("b", ManagementPage.Workspace) }
                compose.waitUntil(8000) { vm.state.value.loaded }
                compose.runOnIdle { vm.removeWorkspace(true) {} }
                compose.waitUntil(3000) { !vm.state.value.busy && vm.state.value.data["container"]?.jsonObject?.get("removal")?.jsonObject?.get("state")?.jsonPrimitive?.content == "removing" }
                compose.onNodeWithText("正在删除").assertExists()
                compose.onNodeWithText("工作空间已删除").assertDoesNotExist()
                compose.waitUntil(7000) { vm.state.value.data["container"]?.jsonObject?.get("removal")?.jsonObject?.get("state")?.jsonPrimitive?.content == "failed" }
                compose.onNodeWithText("重试删除").assertExists()
                compose.onNodeWithText("保留工作空间").assertIsEnabled()
                screenshot("update-workspace-removal-failed")
                compose.onNodeWithText("保留工作空间").performClick()
                compose.waitUntil(8000) { !vm.state.value.busy && vm.state.value.data["container"]?.jsonObject?.containsKey("removal") == false }
                assertEquals(1, server.requests.count { it.path.endsWith("/removal/cancel") && it.method == "POST" })
                compose.runOnIdle { vm.removeWorkspace(false) {} }
                compose.waitUntil(9000) { vm.state.value.notice == "工作空间已删除" }
                assertTrue(vm.state.value.data["container"]!!.jsonObject.isEmpty())
                assertEquals(2, deletions.get())
                assertEquals(listOf("true", "false"), server.requests.filter { it.method == "DELETE" }.map { it.query("preserve_data") })
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun providerImportRequiresConfirmationAndDoesNotRetryACreatedProvider() {
        FixtureServer { request -> when (request.path) {
            "/provider-templates" -> Reply(body = """[{"id":"other-template","key":"newapi","domain":"llm","driver":"anthropic-messages"}]""")
            "/providers" -> Reply(201, """{"id":"provider-1"}""")
            "/providers/provider-1/import-models" -> Reply(503, """{"code":"agent.provider_overloaded"}""")
            else -> Reply()
        } }.use { server ->
            val vm = ProviderImportViewModel(repository(server))
            val payload = ProviderImport("导入测试", "https://provider.example.test/v1", "fixture-only-1234", "openai-responses", "newapi")
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ProviderImportDialog(payload, "Fixture · 本地测试", state, { vm.confirm(payload) }, {}, {}) } }
            try {
                compose.onNodeWithText("API key · ••••1234").assertExists()
                compose.onNodeWithText(payload.apiKey, substring = true).assertDoesNotExist()
                assertTrue(server.requests.isEmpty())
                screenshot("update-provider-import-preview")
                compose.onNodeWithText("确认添加").performClick()
                compose.waitUntil(8000) { vm.state.value.createdId != null }
                assertFalse(vm.state.value.modelsImported)
                val created = server.requests.single { it.path == "/providers" && it.method == "POST" }
                assertEquals("openai-responses", json.parseToJsonElement(created.body).jsonObject["client_type"]!!.jsonPrimitive.content)
                assertEquals(0, server.requests.count { it.path == "/providers/from-template" })
                compose.onNodeWithText("查看供应商").assertExists()
                compose.runOnIdle { vm.confirm(payload) }
                assertEquals(1, server.requests.count { it.path == "/providers" && it.method == "POST" })
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun remoteFolderBrowseAndRenameKeepTheServerTargetAndPath() {
        FixtureServer { request -> when (request.path) {
            "/bots/b/workspace-targets" -> Reply(body = """{"targets":[{"target_id":"native","kind":"native","name":"云端"},{"target_id":"pc","kind":"remote","name":"远程电脑","online":true}]}""")
            "/bots/b/workdirs/directories" -> if (request.query("workspace_target_id") == "pc") {
                if (request.query("path").isNullOrBlank()) Reply(body = """{"workspace_target_id":"pc","path":"C:\\Users\\Alice","directories":[{"name":"projects","path":"C:\\Users\\Alice\\projects"}]}""")
                else Reply(body = """{"workspace_target_id":"pc","path":"C:\\Users\\Alice\\projects","directories":[]}""")
            } else Reply(body = """{"workspace_target_id":"native","path":"/data","directories":[]}""")
            "/bots/b/workdirs" -> Reply(201, """{"id":"w","name":"工程","path":"C:\\Users\\Alice\\projects","workspace_target_id":"pc","target_kind":"remote"}""")
            "/bots/b/workdirs/w" -> Reply(body = """{"id":"w","name":"新名"}""")
            else -> Reply()
        } }.use { server ->
            val vm = FolderEditorViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); FolderEditorSheet(state, vm, {}) } }
            try {
                compose.runOnIdle { vm.open("b", null) }
                compose.waitUntil(8000) { !vm.state.value.loading && vm.state.value.path == "/data" }
                compose.onNodeWithText("远程电脑").performClick()
                compose.waitUntil(8000) { !vm.state.value.loading && vm.state.value.path.startsWith("C:") }
                compose.onNodeWithText("projects").performScrollTo().performClick()
                compose.waitUntil(8000) { !vm.state.value.loading && vm.state.value.path.endsWith("projects") }
                compose.onNode(hasSetTextAction() and hasText("名称")).performTextInput("工程")
                screenshot("update-remote-folder")
                compose.onNodeWithText("保存").performScrollTo().performClick()
                compose.waitUntil(8000) { vm.state.value.saved }
                val create = json.parseToJsonElement(server.requests.single { it.path == "/bots/b/workdirs" }.body).jsonObject
                assertEquals("pc", create["workspace_target_id"]!!.jsonPrimitive.content)
                assertEquals("C:\\Users\\Alice\\projects", create["path"]!!.jsonPrimitive.content)
                assertEquals(0, server.requests.count { it.path.endsWith("/mkdir") })
                compose.runOnIdle { vm.open("b", Workdir("w", name = "工程", path = "C:\\Users\\Alice\\projects", workspaceTargetId = "pc", targetKind = "remote")) }
                compose.onNode(hasSetTextAction() and hasText("名称")).performTextReplacement("新名")
                compose.onNodeWithText("保存").performScrollTo().performClick()
                compose.waitUntil(8000) { vm.state.value.saved }
                val rename = server.requests.single { it.path == "/bots/b/workdirs/w" }
                assertEquals("PATCH", rename.method)
                assertEquals(setOf("name"), json.parseToJsonElement(rename.body).jsonObject.keys)
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun messageLinksRouteFilesAndPreviewsThroughTheWorkspace() {
        var opened: WorkspaceLink? = null
        compose.setContent { MemohTheme { Surface {
            CompositionLocalProvider(LocalWorkspaceLinkHandler provides { opened = it }) { Column {
                MemohMarkdown.Markdown("[打开交付文件](/data/report.pdf#L10)")
                MemohMarkdown.Markdown("[打开工作区预览](http://127.0.0.1:5173/result?q=1)")
                MemohMarkdown.Markdown("[相对路径](report.md)")
            } }
        } } }
        compose.onNodeWithText("打开交付文件", substring = true).performClick()
        assertEquals(WorkspaceLink.File("/data/report.pdf"), opened)
        compose.onNodeWithText("打开工作区预览", substring = true).performClick()
        assertEquals(WorkspaceLink.Preview("localhost:5173/result?q=1"), opened)
        compose.onNodeWithText("相对路径").assertHasNoClickAction()
    }

    @Test fun contextInspectorSelectsTurnsAndKeepsPagingScopeVisible() {
        var older = false
        val snapshot = ContextLifecycleSnapshot(model = "Model", counts = ContextLifecycleCounts(tokenEstimate = 8000),
            breakdown = listOf(ContextKindUsage("conversation_event", 4, 8000)), budgetPlan = ContextBudgetPlan(16000, 2000),
            selection = ContextSelection(4, 2, 1, mapOf("history_budget" to 2), mapOf("history_budget" to 3000)))
        val page = ContextLifecycleResponse(turns = listOf(
            ContextLifecycleTurn("new", createdAt = "2026-10-13T00:00:00Z", snapshot = snapshot),
            ContextLifecycleTurn("old", createdAt = "2026-10-12T23:00:00Z", snapshot = ContextLifecycleSnapshot(counts = ContextLifecycleCounts(tokenEstimate = 4000)))
        ), hasMore = true, aggregateScope = "returned_page", aggregates = ContextLifecycleAggregates(totalCacheReadTokens = 5000))
        var state by mutableStateOf(ChatUiState(session = Session("s"), contextLifecycle = page))
        compose.setContent { MemohTheme { Surface { ContextInspectorSheet(state, { more ->
            older = more
            if (more) state = state.copy(contextLifecycle = page.copy(limit = 200))
        }, {}) } } }
        compose.onNodeWithText("8.0K / 16.0K tokens · 估算").assertExists()
        compose.onNodeWithText("第 1 轮").performClick()
        compose.onNodeWithText("4.0K / 窗口未知 tokens · 估算").assertExists()
        compose.onNodeWithText("第 2 轮").performClick()
        screenshot("update-context-inspector")
        compose.onNodeWithText("加载至最近 200 轮").performScrollTo().performClick()
        assertTrue(older)
        compose.onNodeWithText("仍可能有更早的历史，以上统计不代表整个会话。").performScrollTo().assertIsDisplayed()
    }
}

private data class Reply(val status: Int = 200, val body: String = "{}")
private data class FixtureRequest(val method: String, val target: String, val body: String) {
    val path get() = target.substringBefore('?')
    fun query(key: String): String? = target.substringAfter('?', "").split('&').firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
}
private class FixtureServer(private val respond: (FixtureRequest) -> Reply) : AutoCloseable {
    private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val origin = "http://localhost:${socket.localPort}"
    val requests = CopyOnWriteArrayList<FixtureRequest>()
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
            val request = FixtureRequest(first[0], first.getOrElse(1) { "/" }, String(bytes, Charsets.UTF_8))
            requests += request
            val reply = respond(request)
            val body = reply.body.toByteArray()
            val status = "HTTP/1.1 ${reply.status} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().apply { write(status.toByteArray()); write(body); flush() }
        } } catch (_: Exception) { if (socket.isClosed) break }
    }.apply { isDaemon = true; start() }
    override fun close() { socket.close(); worker.join(1000) }
}
