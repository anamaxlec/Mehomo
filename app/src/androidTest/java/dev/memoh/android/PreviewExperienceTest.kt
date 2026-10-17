package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.*
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import dev.memoh.feature.chat.components.ModelMenu
import dev.memoh.feature.sessions.WorkspaceScreen
import dev.memoh.feature.sessions.WorkspaceViewModel
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local fixture pages and credentials only; no connection to a user's Cloud account. */
@RunWith(AndroidJUnit4::class)
class PreviewExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun reasoningChoicesFitTheMenuAtLargeFontSizesAndRemainSelectable() {
        var allEfforts by mutableStateOf(false)
        var selected by mutableStateOf("low")
        val short = listOf("low", "high", "max")
        val full = listOf("disable", "minimal", "low", "medium", "high", "xhigh", "max")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MemohTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                    ModelMenu(true, {}, listOf(ChatModel("m", name = "DeepSeek v4.1 Flash",
                        reasoning = ReasoningOptions(true, false, "low", if (allEfforts) full else short))),
                        selectedModelId = "m", reasoningEffort = selected, onSelectModel = {}, onSelectEffort = { selected = it.orEmpty() })
                } }
            }
        }
        fun verify(efforts: List<String>) {
            val menu = compose.onNode(isPopup()).getUnclippedBoundsInRoot()
            efforts.forEach { effort ->
                val choice = compose.onNodeWithText(reasoningEffortLabel(effort))
                    .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val bounds = choice.getUnclippedBoundsInRoot()
                assertTrue("$effort must fit inside the menu: $bounds / $menu", bounds.left >= menu.left && bounds.right <= menu.right)
                val label = compose.onNodeWithText(reasoningEffortLabel(effort), useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("$effort must stay on one text line", label.bottom - label.top < 30.dp)
            }
        }
        verify(short)
        compose.onNodeWithText("最大").performClick().assertIsOn()
        assertEquals("max", selected)
        screenshot("reasoning-three-choices-large-font")
        compose.runOnIdle { allEfforts = true }
        verify(full)
        compose.onNodeWithText("关闭").performClick().assertIsOn()
        assertEquals("disable", selected)
        screenshot("reasoning-seven-choices-large-font")
    }

    @Test fun newEmptyConversationKeepsItsSkeletonAcrossReconnectAndInitialRecommendations() {
        var state by mutableStateOf(ChatUiState(session = Session("new"), historyLoading = false,
            hasMoreHistory = false, socketStatus = SocketStatus.Connecting))
        compose.setContent { MemohTheme { ChatScreen(state, {}, {}, {}, {}, {},
            onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
            onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        compose.onNodeWithContentDescription("正在加载会话").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(socketStatus = SocketStatus.Reconnecting) }
        compose.onNodeWithContentDescription("正在加载会话").assertIsDisplayed()
        compose.onNodeWithText("开始对话").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(socketStatus = SocketStatus.Connected, awaitingSnapshot = false,
                runtime = RuntimeState(sessionId = "new"), suggestionsLoading = true)
        }
        compose.onNodeWithContentDescription("正在加载会话").assertIsDisplayed()
        compose.onNodeWithText("开始对话").assertDoesNotExist()
        screenshot("new-session-loading-recommendations")
        compose.runOnIdle { state = state.copy(suggestionsLoading = false,
            starterPrompts = StarterPromptsResponse("fixture", listOf(StarterPrompt("写一份计划", "先问我今天的安排")))) }
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        compose.onNodeWithText("写一份计划").assertIsDisplayed()
        // A subsequent refresh must keep this already-visible page in place.
        compose.runOnIdle { state = state.copy(suggestionsLoading = true) }
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        compose.onNodeWithText("写一份计划").assertIsDisplayed()
    }

    @Test fun initialRecommendationFailureRevealsUsableFallbackSuggestions() {
        var state by mutableStateOf(ChatUiState(session = Session("failed-recommendations"), historyLoading = false,
            awaitingSnapshot = false, suggestionsLoading = true, hasMoreHistory = false, socketStatus = SocketStatus.Connected))
        compose.setContent { MemohTheme { ChatScreen(state, {}, {}, {}, {}, {},
            onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
            onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        compose.onNodeWithContentDescription("正在加载会话").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(suggestionsLoading = false) }
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        compose.onNodeWithText("开始对话").assertIsDisplayed()
        compose.onNodeWithText("更多示例").assertIsEnabled()
    }

    @Test fun htmlPreviewAuthenticatesRelativeAssetsFollowsRedirectsAndRunsJavaScript() {
        PreviewFixture().use { server ->
            val vm = WorkspaceViewModel(repository(server))
            compose.setContent { MemohTheme { WorkspaceScreen("b", "", WorkspaceSurface.Browser, vm,
                onBack = {}, initialAddress = "localhost:8124/") } }
            try {
                compose.waitUntil(12_000) { readWeb("document.getElementById('status')?.textContent").contains("HTML 资源加载完成") }
                assertNull(vm.state.value.error)
                val resources = server.requests.filter { it.first.startsWith("/preview/") }
                assertTrue(resources.map { it.first }.containsAll(listOf("/preview/start", "/preview/nested/index.html",
                    "/preview/nested/theme.css", "/preview/nested/app.js", "/preview/nested/data.json", "/preview/nested/cover.svg")))
                resources.forEach { assertTrue("Missing auth for ${it.first}", it.second.contains("Authorization: Bearer fixture-only", true)) }
                assertTrue(readWeb("getComputedStyle(document.getElementById('card')).borderRadius").contains("24px"))
                assertTrue(readWeb("location.pathname").contains("/preview/nested/index.html"))
                readWeb("document.getElementById('action').click()")
                compose.waitUntil(5_000) { readWeb("document.getElementById('action').textContent").contains("交互已响应") }
                screenshot("html-authenticated-interactive-preview")
            } finally { vm.viewModelScope.cancel() }
        }
    }

    private fun readWeb(script: String): String {
        var result = ""
        val done = CountDownLatch(1)
        fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
        compose.runOnIdle {
            val web = find(compose.activity.window.decorView)
            if (web == null) done.countDown() else web.evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        return result
    }

    private fun repository(server: PreviewFixture): SessionRepository {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "preview-${System.nanoTime()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        val credentials = CredentialStore(context, json)
        credentials.saveAccount(StoredAccount(prefix, server.origin, "self-hosted", username = "Fixture"))
        credentials.saveToken(prefix, "fixture-only", null)
        return SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(200) // WebView's DOM callback can precede its next drawn frame.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

private class PreviewFixture : AutoCloseable {
    private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val origin = "http://localhost:${socket.localPort}"
    val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val worker = Thread {
        while (!socket.isClosed) try { socket.accept().use { client ->
            client.soTimeout = 5_000
            val input = client.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) { val next = input.read(); if (next < 0) break; header.append(next.toChar()) }
            val lines = header.toString().split("\r\n")
            val path = lines.first().split(' ').getOrElse(1) { "/" }.substringBefore('?')
            val size = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            repeat(size) { input.read() }
            requests += path to header.toString()
            var type = "application/json"
            var status = 200
            var extra = ""
            val body = when {
                path.startsWith("/preview/") && !header.contains("Authorization: Bearer fixture-only", true) -> {
                    status = 401; """{"code":"INVALID_CREDENTIALS"}"""
                }
                path == "/bots/b/container/browser/sessions" && lines.first().startsWith("POST ") ->
                    """{"id":"fixture-preview","url":"$origin/preview/start"}"""
                path == "/preview/start" -> { status = 302; extra = "Location: /preview/nested/index.html\r\n"; "" }
                path == "/preview/nested/index.html" -> { type = "text/html"; """<!doctype html><html lang="zh"><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="theme.css"><script defer src="app.js"></script><body><h1>HTML 产物预览</h1><section id="card"><img src="cover.svg" width="64" height="64"><h2 id="status">资源加载中</h2><p>相对路径、样式和脚本来自需要认证的云端端口。</p><button id="action" onclick="this.textContent='交互已响应'">试一下</button></section></body></html>""" }
                path == "/preview/nested/theme.css" -> { type = "text/css"; "body{margin:24px;background:#f3f5ed;color:#1c251a;font-family:system-ui}#card{border-radius:24px;background:#d6efc0;padding:24px}button{padding:14px 24px;border:0;border-radius:28px;background:#37672a;color:white}" }
                path == "/preview/nested/app.js" -> { type = "text/javascript"; "fetch('data.json').then(r=>r.json()).then(v=>document.getElementById('status').textContent=v.message)" }
                path == "/preview/nested/data.json" -> """{"message":"HTML 资源加载完成"}"""
                path == "/preview/nested/cover.svg" -> { type = "image/svg+xml"; """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><circle cx="32" cy="32" r="30" fill="#37672a"/><path d="m18 33 10 10 19-22" fill="none" stroke="white" stroke-width="5"/></svg>""" }
                else -> "{}"
            }.toByteArray(Charsets.UTF_8)
            client.getOutputStream().apply {
                write("HTTP/1.1 $status Fixture\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                write(body); flush()
            }
        } } catch (_: Exception) { if (socket.isClosed) break }
    }.apply { isDaemon = true; start() }
    override fun close() { socket.close(); worker.join(1_000) }
}
