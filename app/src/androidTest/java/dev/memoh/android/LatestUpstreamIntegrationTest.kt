package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
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
import dev.memoh.feature.bots.*
import dev.memoh.feature.chat.ChatViewModel
import dev.memoh.feature.chat.ChatSocketFactory
import dev.memoh.feature.chat.components.UserInputPanel
import dev.memoh.feature.sessions.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LatestUpstreamIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val json = Json { ignoreUnknownKeys = true }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val grok = apiBody("id" to "g", "name" to "Grok", "runtime" to "grok-build", "metadata" to apiBody("auth" to "grok_account"))
    private val devicePath = "/bots/b/agents/g/login/device"

    private fun repository(server: UpstreamFixture): SessionRepository {
        val prefix = "latest-upstream-${System.nanoTime()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        val store = CredentialStore(isolated, json)
        store.saveAccount(StoredAccount(prefix, server.origin, "self-hosted", username = "Fixture"))
        store.saveToken(prefix, "fixture-only", null)
        return SessionRepository(store, json, CloudAuth(OkHttpClient(), json))
    }

    private fun base(request: UpstreamRequest): UpstreamReply = when (request.path) {
        "/bots/b" -> UpstreamReply(body = """{"id":"b","name":"Fixture","current_user_permissions":["manage","chat"]}""")
        "/bots/b/agents" -> UpstreamReply(body = apiBody("items" to JsonArray(listOf(grok))).toString())
        "/acp/profiles" -> UpstreamReply(body = """{"items":[]}""")
        else -> UpstreamReply()
    }

    private fun open(vm: ManagementViewModel) {
        compose.runOnIdle { vm.open("b", ManagementPage.Agents) }
        compose.waitUntil(8000) { vm.state.value.loaded }
    }

    @Test fun grokDeviceLoginSurvivesBackAndCompletesWithoutAStagedClaim() {
        val status = AtomicReference("pending")
        UpstreamFixture { request -> when (request.path) {
            "$devicePath/authorize" -> UpstreamReply(body = """{"login_id":"fixture-login","user_code":"DEMO-1234","verification_url":"https://example.com/activate"}""")
            "$devicePath/poll" -> UpstreamReply(body = apiBody("status" to status.get()).toString())
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
            try {
                open(vm)
                compose.runOnIdle { vm.authorizeDeviceAgent(grok) }
                compose.waitUntil(8000) { vm.state.value.authorization != null }
                compose.onNodeWithText("DEMO-1234").assertIsDisplayed()
                compose.onNodeWithContentDescription("复制授权代码").assertIsDisplayed()
                screenshot("grok-device-authorization")
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithText("DEMO-1234").assertIsDisplayed()
                assertFalse(compose.activity.isFinishing)
                status.set("success")
                compose.waitUntil(8000) { vm.state.value.notice == "Agent 已连接" }
                assertNull(vm.state.value.authorization)
                assertFalse(server.requests.any { it.path == "/agent-authorizations" || it.path.endsWith("/credential/claim") || it.path.endsWith("/cancel") })
                assertEquals("fixture-login", json.parseToJsonElement(server.requests.first { it.path.endsWith("/poll") }.body).jsonObject["login_id"]!!.jsonPrimitive.content)
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun cancellingAnInFlightAuthorizeReclaimsTheLateLoginWithoutReopeningTheDialog() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        UpstreamFixture { request -> when (request.path) {
            "$devicePath/authorize" -> {
                entered.countDown(); release.await(5, TimeUnit.SECONDS)
                UpstreamReply(body = """{"login_id":"late-login","user_code":"LATE-0000","verification_url":"https://example.com/activate"}""")
            }
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            try {
                open(vm)
                compose.runOnIdle { vm.authorizeDeviceAgent(grok) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                compose.runOnIdle { vm.cancelAuthorization() }
                release.countDown()
                compose.waitUntil(8000) { server.requests.any { it.path == "$devicePath/cancel" } }
                assertNull(vm.state.value.authorization)
                assertFalse(vm.state.value.busy)
                assertFalse(server.requests.any { it.path == "$devicePath/poll" })
            } finally { release.countDown(); vm.viewModelScope.cancel() }
        }
    }

    @Test fun addingGrokBuildSavesItsNativeRuntimeAndAccountMode() {
        UpstreamFixture(::base).use { server ->
            val vm = ManagementViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
            try {
                open(vm)
                compose.onNodeWithText("添加 Agent").performClick()
                compose.onNodeWithText("Codex").performClick()
                compose.onNodeWithText("Grok Build").performClick()
                compose.onNodeWithText("Grok 账号").assertIsDisplayed()
                compose.onNode(hasSetTextAction() and hasText("名称")).performTextInput("我的 Grok")
                screenshot("grok-agent-form")
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(8000) { server.requests.any { it.path == "/bots/b/agents" && it.method == "POST" } }
                val body = json.parseToJsonElement(server.requests.first { it.method == "POST" && it.path == "/bots/b/agents" }.body).jsonObject
                assertEquals("grok-build", body["runtime"]!!.jsonPrimitive.content)
                assertEquals("grok_account", body["metadata"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
                assertFalse(body["enabled"]!!.jsonPrimitive.boolean)
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun grokApiKeyUsesXaiAndChangesAuthBeforeClaimWhileAnAttachedCredentialBlocksSwitching() {
        UpstreamFixture { request -> when (request.path) {
            "/agent-authorizations" -> UpstreamReply(body = """{"id":"fixture-auth","status":"ready"}""")
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            try {
                open(vm)
                val attached = JsonObject(grok + ("agent_credential_id" to JsonPrimitive("existing")))
                compose.runOnIdle { vm.authorizeAgent(attached, "xai_api_key", "fixture-key") }
                assertEquals("请先断开当前认证，再切换认证方式", vm.state.value.error)
                assertFalse(server.requests.any { it.path == "/agent-authorizations" })
                compose.runOnIdle { vm.authorizeAgent(grok, "xai_api_key", "fixture-key") }
                compose.waitUntil(8000) { vm.state.value.notice == "Agent 已连接" }
                val request = server.requests.first { it.path == "/agent-authorizations" }
                val body = json.parseToJsonElement(request.body).jsonObject
                assertEquals("grok-build", body["runtime"]!!.jsonPrimitive.content)
                assertEquals("xai_api_key", body["auth_kind"]!!.jsonPrimitive.content)
                assertEquals("fixture-key", body["secret"]!!.jsonObject["api_key"]!!.jsonPrimitive.content)
                val patch = server.requests.first { it.method == "PATCH" }
                assertEquals("api_key", json.parseToJsonElement(patch.body).jsonObject["metadata"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
                assertTrue(server.requests.indexOf(patch) < server.requests.indexOf(request))
                assertEquals(1, server.requests.count { it.path.endsWith("/credential/claim") })
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun grokExpiredLoginStopsPollingAndNeverUsesUnsupportedStagedAccountAuth() {
        UpstreamFixture { request -> when (request.path) {
            "$devicePath/authorize" -> UpstreamReply(body = """{"login_id":"expired","user_code":"DEMO","verification_url":"https://example.com/activate"}""")
            "$devicePath/poll" -> UpstreamReply(body = """{"status":"unknown"}""")
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            try {
                open(vm)
                compose.runOnIdle { vm.authorizeDeviceAgent(grok) }
                compose.waitUntil(8000) { vm.state.value.error != null }
                assertNull(vm.state.value.authorization)
                assertFalse(vm.state.value.busy)
                assertFalse(server.requests.any { it.path == "/agent-authorizations" })
                assertEquals(1, server.requests.count { it.path == "$devicePath/poll" })
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun normalizedDirectRuntimeQuestionsKeepMultipleChoicesAndACustomAnswer() {
        val input = json.decodeFromString<UIUserInput>("""{"user_input_id":"grok-question","status":"pending","questions":[{"id":"q1","text":"选择需要的内容","kind":"multi_select","allow_custom":true,"required":true,"options":[{"id":"a","label":"文档"},{"id":"b","label":"示例"}]}]}""")
        var answers: List<WSUserInputAnswer>? = null
        compose.setContent { MemohTheme { UserInputPanel(input, { answers = it }, {}) } }
        compose.onNodeWithText("文档").performClick()
        compose.onNodeWithText("示例").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("补充中文说明")
        compose.onNodeWithText("提交").performClick()
        assertEquals(listOf("a", "b"), answers!!.single().optionIds)
        assertEquals("补充中文说明", answers!!.single().customText)
    }

    @Test fun codexOnAnOlderServerFallsBackToItsExistingStagedAuthorization() {
        UpstreamFixture { request -> when {
            request.path.endsWith("/login/device/authorize") -> UpstreamReply(404)
            request.path == "/agent-authorizations" -> UpstreamReply(body = """{"id":"legacy","status":"ready"}""")
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            try {
                open(vm)
                compose.runOnIdle { vm.authorizeDeviceAgent(apiBody("id" to "c", "runtime" to "codex", "metadata" to apiBody("auth" to "chatgpt"))) }
                compose.waitUntil(8000) { vm.state.value.notice == "Agent 已连接" }
                assertEquals("openai_codex_oauth", json.parseToJsonElement(server.requests.first { it.path == "/agent-authorizations" }.body).jsonObject["auth_kind"]!!.jsonPrimitive.content)
                assertTrue(server.requests.any { it.path == "/bots/b/agents/c/credential/claim" })
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun grokModelsComeFromItsAgentCatalogAndKeepTheConfiguredThinkingEffort() {
        UpstreamFixture { request -> when (request.path) {
            "/bots/b/sessions/s" -> UpstreamReply(body = """{"id":"s","bot_id":"b","runtime_type":"grok-build","bot_agent_id":"g"}""")
            "/bots/b/agents/g/models" -> UpstreamReply(body = """{"models":[{"id":"grok-build-model","name":"Grok Build Model","default":true,"default_reasoning_effort":"high","reasoning_efforts":[{"id":"low","name":"低"},{"id":"high","name":"高"}]}],"configured_model_id":"grok-build-model","configured_reasoning_effort":"high"}""")
            else -> base(request)
        } }.use { server ->
            val repository = repository(server)
            val root = File(context.cacheDir, "grok-test-${System.nanoTime()}")
            val vm = ChatViewModel(repository, json, ChatSocketFactory(OkHttpClient(), json, CloudAuth(OkHttpClient(), json)), SettingsStore(context),
                ChatHistoryStore(File(root, "history"), json), AttachmentDraftStore(File(root, "drafts"), json))
            try {
                compose.runOnIdle { vm.open("b", "s") }
                compose.waitUntil(8000) { !vm.state.value.modelCatalogLoading && vm.state.value.models.isNotEmpty() }
                assertEquals("grok-build-model", vm.state.value.selectedModelId)
                assertEquals("high", vm.state.value.reasoningEffort)
                assertEquals(listOf("low", "high"), vm.state.value.models.single().efforts)
                assertFalse(server.requests.any { it.path == "/models" || it.path == "/providers" })
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun desktopResumesOnlyARequestedViewerAndClearsClipboardOnDisconnect() {
        UpstreamFixture { request -> if (request.path.endsWith("/container/display")) UpstreamReply(body = """{"available":true}""") else base(request) }.use { server ->
            val vm = WorkspaceViewModel(repository(server))
            val starts = AtomicInteger()
            compose.runOnIdle { vm.viewModelScope.launch { vm.commands.collect { if (it == WorkspaceCommand.StartDesktop) starts.incrementAndGet() } } }
            try {
                compose.runOnIdle { vm.open("b", WorkspaceSurface.Desktop); vm.rendererReady() }
                compose.waitUntil(8000) { !vm.state.value.loading }
                assertEquals(0, starts.get())
                compose.runOnIdle { vm.connectDesktop() }
                compose.waitUntil { starts.get() == 1 }
                compose.runOnIdle { vm.desktopStatus("connected"); vm.remoteClipboard("中文🙂"); vm.stop() }
                assertNull(vm.state.value.remoteClipboard)
                compose.runOnIdle { vm.open("b", WorkspaceSurface.Desktop); vm.rendererReady() }
                compose.waitUntil(8000) { starts.get() == 2 }
                compose.runOnIdle { vm.desktopStatus("connected"); vm.disconnect(); vm.stop(); vm.open("b", WorkspaceSurface.Desktop); vm.rendererReady() }
                compose.waitUntil(8000) { !vm.state.value.loading }
                assertFalse(vm.state.value.connecting)
                assertEquals(2, starts.get())
            } finally { compose.runOnIdle { vm.stop(); vm.viewModelScope.cancel() } }
        }
    }

    @Test fun aLateDesktopOfferIsReleasedOnItsOriginalBotAfterNavigation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        UpstreamFixture { request -> when {
            request.path.endsWith("/container/display") -> UpstreamReply(body = """{"available":true}""")
            request.path == "/bots/b/container/display/webrtc/offer" -> {
                entered.countDown(); release.await(5, TimeUnit.SECONDS)
                UpstreamReply(body = """{"type":"answer","sdp":"fixture","session_id":"late-display"}""")
            }
            else -> base(request)
        } }.use { server ->
            val vm = WorkspaceViewModel(repository(server))
            try {
                compose.runOnIdle { vm.open("b", WorkspaceSurface.Desktop); vm.rendererReady() }
                compose.waitUntil(8000) { !vm.state.value.loading }
                compose.runOnIdle { vm.connectDesktop(); vm.desktopOffer("fixture") }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                compose.runOnIdle { vm.stop(); vm.open("other", WorkspaceSurface.Desktop); vm.rendererReady() }
                release.countDown()
                compose.waitUntil(8000) { server.requests.any { it.path == "/bots/b/container/display/sessions/late-display" && it.method == "DELETE" } }
                assertEquals("other", vm.state.value.botId)
                assertFalse(vm.state.value.connected)
                assertFalse(server.requests.any { it.path.startsWith("/bots/other/container/display/sessions/") })
            } finally { release.countDown(); compose.runOnIdle { vm.stop(); vm.viewModelScope.cancel() } }
        }
    }

    @Test fun desktopComposerKeepsUnsentChineseAndExposesExplicitClipboardActions() {
        UpstreamFixture { request -> if (request.path.endsWith("/container/display")) UpstreamReply(body = """{"available":true}""") else base(request) }.use { server ->
            val vm = WorkspaceViewModel(repository(server))
            compose.setContent { MemohTheme { WorkspaceScreen("b", "Fixture", WorkspaceSurface.Desktop, vm) } }
            try {
                compose.waitUntil(8000) { vm.state.value.rendererReady && !vm.state.value.loading }
                compose.runOnIdle { vm.connectDesktop(); vm.desktopStatus("connected") }
                compose.onNodeWithText("粘贴到桌面").assertIsEnabled()
                compose.onNodeWithText("复制桌面内容").assertIsNotEnabled()
                compose.onNode(hasSetTextAction()).performTextInput("保留中文草稿")
                compose.onNodeWithContentDescription("发送输入").performClick()
                compose.waitUntil(8000) { vm.state.value.error?.startsWith("输入通道尚未就绪") == true }
                compose.onNode(hasSetTextAction()).assertTextContains("保留中文草稿")
                compose.runOnIdle { vm.remoteClipboard("来自桌面") }
                compose.onNodeWithText("复制桌面内容").assertIsEnabled()
                compose.onNode(hasSetTextAction()).performImeAction()
                screenshot("desktop-input-and-clipboard")
            } finally { compose.runOnIdle { vm.stop(); vm.viewModelScope.cancel() } }
        }
    }

    @Test fun manualDisconnectCancelsAnAutomaticDesktopRetry() {
        UpstreamFixture { request -> if (request.path.endsWith("/container/display")) UpstreamReply(body = """{"available":true}""") else base(request) }.use { server ->
            val vm = WorkspaceViewModel(repository(server))
            val starts = AtomicInteger()
            compose.runOnIdle { vm.viewModelScope.launch { vm.commands.collect { if (it == WorkspaceCommand.StartDesktop) starts.incrementAndGet() } } }
            try {
                compose.runOnIdle { vm.open("b", WorkspaceSurface.Desktop); vm.rendererReady() }
                compose.waitUntil(8000) { !vm.state.value.loading }
                compose.runOnIdle { vm.connectDesktop() }
                compose.waitUntil { starts.get() == 1 }
                // Do not wait for UI idleness between scheduling and cancelling the one-second retry.
                compose.runOnUiThread {
                    vm.desktopStatus("failed")
                    assertTrue(vm.state.value.connecting)
                    vm.disconnect()
                }
                Thread.sleep(1200) // Cross the first retry deadline, with no active retry remaining.
                compose.waitForIdle()
                assertFalse(vm.state.value.connecting)
                assertEquals(1, starts.get())
            } finally { compose.runOnIdle { vm.stop(); vm.viewModelScope.cancel() } }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(300) // Wait for Android's dialog window entrance, outside Compose's animation clock.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
