package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    @Test fun modelConnectionTestsShowDiagnosticsWithoutReloadingTheModelList() {
        val upstreamDetail = "HTTP 401 · invalid_api_key"
        UpstreamFixture { request -> when (request.path) {
            "/providers" -> UpstreamReply(body = """[{"id":"provider","name":"Fixture LLM","client_type":"openai-completions"}]""")
            "/models" -> UpstreamReply(body = """[{"id":"model","name":"Fixture Model","model_id":"fixture","provider_id":"provider"}]""")
            "/providers/provider/test" -> UpstreamReply(body = """{"status":"auth_error","code":"agent.provider_auth_failed","args":{"upstream":"$upstreamDetail"}}""")
            "/models/model/test" -> UpstreamReply(502, """{"code":"agent.provider_unreachable","args":{"upstream":"upstream timeout"},"request_id":"req-model"}""")
            else -> UpstreamReply()
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            compose.setContent { MemohTheme {
                val state by vm.state.collectAsState()
                ManagementScreen(state, vm, {}, {}, {}, {})
            } }
            try {
                compose.runOnIdle { vm.open("b", ManagementPage.Models) }
                compose.waitUntil(8_000) { vm.state.value.loaded }
                compose.onNodeWithContentDescription("Fixture LLM操作").performClick()
                compose.onNodeWithText("测试连接").performClick()
                compose.waitUntil(8_000) { vm.state.value.connectionTest != null }
                compose.onNodeWithText("模型服务拒绝了当前凭据，请检查该服务商的 API Key。").assertIsDisplayed()
                compose.onNodeWithText(upstreamDetail).assertDoesNotExist()
                compose.onNodeWithText("错误详情").performClick()
                compose.onNodeWithText(upstreamDetail).assertIsDisplayed()
                screenshot("provider-test-details")
                compose.onNodeWithText("完成").performClick()

                compose.onNodeWithText("全部模型").performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Fixture Model操作"))
                compose.onNodeWithContentDescription("Fixture Model操作").performClick()
                compose.onNodeWithText("测试模型").performClick()
                compose.waitUntil(8_000) { vm.state.value.connectionTest != null }
                compose.onNodeWithText("错误详情").performClick()
                compose.onNodeWithText("upstream timeout").assertIsDisplayed()
                compose.onNodeWithText("req-model", substring = true).assertIsDisplayed()
                assertEquals(1, server.requests.count { it.path == "/providers" })
                assertEquals(1, server.requests.count { it.path == "/models" })
                assertEquals(listOf("/providers/provider/test", "/models/model/test"),
                    server.requests.filter { it.method == "POST" }.map { it.path })
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun channelSettingsUseTheBatchAndKeepExistingCredentialsWhenStreamingIsDisabled() {
        val credentials = AtomicReference("""{"appId":"fixture","appSecret":"fixture-secret","encryptKey":"keep"}""")
        UpstreamFixture { request -> when (request.path) {
            "/channels" -> UpstreamReply(body = """[{"type":"telegram","display_name":"Telegram"},{"type":"feishu","display_name":"飞书","config_schema":{"fields":{"enableStreaming":{"type":"bool","example":true,"order":1},"appSecret":{"type":"secret","required":true,"title":"App Secret","order":2},"encryptKey":{"type":"secret","title":"Encrypt Key","order":3}}}}]""")
            "/bots/b/channels" -> UpstreamReply(body = """{"items":[{"channel_type":"feishu","credentials":${credentials.get()},"routing":{"keep":"route"}}]}""")
            "/bots/b/channel/feishu" -> {
                credentials.set(json.parseToJsonElement(request.body).jsonObject.getValue("credentials").toString())
                UpstreamReply()
            }
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
            try {
                compose.runOnIdle { vm.open("b", ManagementPage.Channels) }
                compose.waitUntil(8000) { vm.state.value.loaded }
                val feishu = compose.onNodeWithTag("channel-row-feishu").assertIsDisplayed()
                val telegram = compose.onNodeWithTag("channel-row-telegram").assertIsDisplayed()
                assertTrue(feishu.fetchSemanticsNode().boundsInRoot.top < telegram.fetchSemanticsNode().boundsInRoot.top)
                feishu.performClick()
                compose.onNodeWithText("流式回复").assertIsDisplayed()
                compose.onNodeWithText("fixture-secret").assertDoesNotExist()
                compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).assertIsOn().performClick().assertIsOff()
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(8000) { vm.state.value.notice == "已保存" }
                val save = server.requests.single { it.path == "/bots/b/channel/feishu" }
                assertEquals("PUT", save.method)
                val payload = json.parseToJsonElement(save.body).jsonObject
                assertEquals(JsonPrimitive("route"), payload.getValue("routing").jsonObject["keep"])
                val saved = payload.getValue("credentials").jsonObject
                assertEquals(JsonPrimitive(false), saved["enableStreaming"])
                assertEquals(JsonPrimitive("fixture-secret"), saved["appSecret"])
                assertEquals(JsonPrimitive("keep"), saved["encryptKey"])
                assertEquals(2, server.requests.count { it.path == "/bots/b/channels" })
                assertFalse(server.requests.any { it.method == "GET" && it.path.startsWith("/bots/b/channel/") })
                feishu.performClick()
                compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).assertIsOff()
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun missingCredentialsLeaveAConfiguredChannelReadOnly() {
        UpstreamFixture { request -> when (request.path) {
            "/channels" -> UpstreamReply(body = """[{"type":"feishu","display_name":"飞书"}]""")
            "/bots/b/channels" -> UpstreamReply(body = """{"items":[{"channel_type":"feishu","credentials":null}]}""")
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
            try {
                compose.runOnIdle { vm.open("b", ManagementPage.Channels) }
                compose.waitUntil(8000) { vm.state.value.loaded }
                compose.onNodeWithText("凭据未返回，请刷新").assertIsDisplayed()
                compose.onNodeWithTag("channel-row-feishu").assert(!hasClickAction())
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun loginWithAMissingAgentDependencyOpensTheInstallPromptWithoutInstalling() {
        val agent = JsonObject(grok + ("dependency" to apiBody("dependency_id" to "grok-build")))
        UpstreamFixture { request -> when (request.path) {
            "$devicePath/authorize" -> UpstreamReply(409, """{"code":"agent_dependency_missing","args":{"dep_id":"grok-build"}}""")
            "/bots/b/dependencies/preflight" -> UpstreamReply(body = """{"workspace_state":"running","items":[{"dependency_id":"grok-build","name":"Grok Build","state":"missing"}]}""")
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
            try {
                open(vm)
                compose.runOnIdle { vm.authorizeDeviceAgent(agent) }
                compose.waitUntil(8000) { vm.state.value.dependencyPrompt != null }
                compose.onNodeWithText("安装运行依赖").assertIsDisplayed()
                compose.onNodeWithText("安装并启用").assertIsDisplayed()
                assertFalse(server.requests.any { it.path.endsWith("/install") || it.method == "PATCH" })
            } finally { vm.viewModelScope.cancel() }
        }
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

    @Test fun codexChatGptQuotaComesFromTheGenericQuotaRoute() {
        val chatgpt = apiBody("id" to "c", "name" to "Codex", "runtime" to "codex", "metadata" to apiBody("auth" to "chatgpt"))
        UpstreamFixture { request -> when (request.path) {
            "/bots/b/agents" -> UpstreamReply(body = apiBody("items" to JsonArray(listOf(chatgpt))).toString())
            "/bots/b/agents/c/quota" -> UpstreamReply(body = """{"limit_reached":false,"windows":[{"used_percent":30,"window_minutes":300}]}""")
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
            try {
                open(vm)
                screenshot("codex-quota-agents")
                // A ChatGPT sign-in lives in the Agent's workspace, so no credential
                // points at it and the row must still read as connected.
                compose.onNode(hasText("已连接", substring = true)).assertIsDisplayed()
                compose.onNodeWithText("查看 Codex 的账号额度").assertIsDisplayed().performClick()
                compose.waitUntil(8000) { server.requests.any { it.path == "/bots/b/agents/c/quota" } }
                compose.waitUntil(8000) { vm.state.value.agentQuota["c"] != null }
                assertEquals(30.0, vm.state.value.agentQuota.getValue("c").windows.single().usedPercent, .0)
                assertFalse(server.requests.any { it.path.endsWith("/codex/usage") })
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun claudeWorkspaceCodeConnectsTheAccountAndDisplaysModelQuota() {
        val signedIn = AtomicReference(false)
        val claude = apiBody("id" to "cl", "name" to "Claude", "runtime" to "claude-code", "enabled" to true,
            "reports_quota" to true, "metadata" to apiBody("auth" to "claude_account"))
        val agentPath = "/bots/b/agents/cl"
        UpstreamFixture { request -> when (request.path) {
            "/bots/b/agents" -> UpstreamReply(body = apiBody("items" to JsonArray(listOf(claude))).toString())
            "$agentPath/account" -> UpstreamReply(body = apiBody("signed_in" to signedIn.get()).toString())
            "$agentPath/login/device/authorize" -> UpstreamReply(body = """{"login_id":"claude-login","code_required":true,"verification_url":"https://example.com/authorize"}""")
            "$agentPath/login/device/code" -> {
                signedIn.set(true)
                UpstreamReply(204, "")
            }
            "$agentPath/quota" -> UpstreamReply(body = """{"limit_reached":false,"windows":[{"used_percent":42,"window_minutes":10080,"model":"Sonnet"}]}""")
            "$agentPath/credential" -> {
                signedIn.set(false)
                UpstreamReply(204, "")
            }
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
            try {
                open(vm)
                compose.waitUntil(8000) { vm.state.value.agentAccounts["cl"]?.signedIn == false }
                compose.onNodeWithText("查看 Claude 的账号额度").assertDoesNotExist()
                compose.onNodeWithContentDescription("Claude操作").performClick()
                compose.onNodeWithText("账号授权").performClick()
                compose.waitUntil(8000) { vm.state.value.authorization != null }
                compose.onNodeWithText("打开授权页").assertIsDisplayed()
                compose.onNodeWithText("连接").assertIsNotEnabled()
                compose.onNode(hasSetTextAction()).performTextInput("example-code#state")
                compose.onNodeWithText("连接").performClick()
                compose.waitUntil(8000) { vm.state.value.authorization == null && vm.state.value.agentAccounts["cl"]?.signedIn == true }
                val submission = server.requests.single { it.path == "$agentPath/login/device/code" }
                assertEquals("POST", submission.method)
                assertEquals(apiBody("login_id" to "claude-login", "code" to "example-code#state"), json.parseToJsonElement(submission.body))
                assertFalse(server.requests.any { it.path == "/agent-authorizations" || it.path.endsWith("/poll") || it.path.endsWith("/cancel") || it.path.endsWith("/credential/claim") })
                compose.onNodeWithText("查看 Claude 的账号额度").performClick()
                compose.waitUntil(8000) { vm.state.value.agentQuota["cl"] != null }
                compose.onNodeWithText("Claude 账号额度").assertIsDisplayed()
                compose.onNodeWithText("1 周 · Sonnet").assertIsDisplayed()
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithContentDescription("Claude操作").performClick()
                compose.onNodeWithText("断开认证").performClick()
                compose.onNodeWithText("确认").performClick()
                compose.waitUntil(8000) { vm.state.value.agentAccounts["cl"]?.signedIn == false }
                assertTrue(server.requests.any { it.path == "$agentPath/credential" && it.method == "DELETE" })
                compose.onNodeWithText("查看 Claude 的账号额度").assertDoesNotExist()
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun rejectingAClaudeCodeClearsTheAttemptAndAllowsAnotherSignIn() {
        val claude = apiBody("id" to "cl", "name" to "Claude", "runtime" to "claude-code", "enabled" to true,
            "metadata" to apiBody("auth" to "claude_account"))
        val device = "/bots/b/agents/cl/login/device"
        UpstreamFixture { request -> when (request.path) {
            "$device/authorize" -> UpstreamReply(body = """{"login_id":"claude-login","code_required":true,"verification_url":"https://example.com/authorize"}""")
            "$device/code" -> UpstreamReply(400, """{"code":"agent_authorization.code_invalid"}""")
            "$device/cancel" -> UpstreamReply(204, "")
            else -> base(request)
        } }.use { server ->
            val vm = ManagementViewModel(repository(server))
            try {
                open(vm)
                compose.runOnIdle { vm.authorizeDeviceAgent(claude) }
                compose.waitUntil(8000) { vm.state.value.authorization != null }
                compose.runOnIdle { vm.submitAgentAuthorizationCode("wrong-code") }
                compose.waitUntil(8000) { vm.state.value.error?.contains("授权码无效") == true }
                assertNull(vm.state.value.authorization)
                assertFalse(vm.state.value.busy)
                compose.runOnIdle { vm.authorizeDeviceAgent(claude) }
                compose.waitUntil(8000) { vm.state.value.authorization != null }
                compose.runOnIdle { vm.cancelAuthorization() }
                compose.waitUntil(8000) { server.requests.count { it.path == "$device/cancel" } == 2 }
                assertEquals(1, server.requests.count { it.path == "$device/code" })
                assertNull(vm.state.value.authorization)
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun newAndExistingScheduleDialogsFitNarrowScreensWithLargeText(): Unit = UpstreamFixture { request ->
        when (request.path) {
            "/bots/b/schedule" -> UpstreamReply(body = """{"items":[{"id":"daily","name":"日程示例","command":"生成简报","pattern":"0 9 * * *","enabled":true}]}""")
            "/bots/b/settings" -> UpstreamReply(body = """{"effective_timezone":"Asia/Singapore"}""")
            "/bots/b/workdirs", "/bots/b/sessions" -> UpstreamReply(body = """{"items":[]}""")
            "/models" -> UpstreamReply(body = "[]")
            else -> base(request)
        }
    }.use { server ->
        val vm = BotFeatureViewModel(repository(server))
        compose.setContent { MemohTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                val state by vm.state.collectAsState()
                Box(Modifier.width(320.dp)) { BotFeatureScreen(state, vm) }
            }
        } }
        try {
            compose.runOnIdle { vm.open("b", BotFeature.Schedules) }
            compose.waitUntil(8_000) { vm.state.value.hasLoaded }
            assertNull(vm.state.value.error)
            compose.onNodeWithText("新建日程").performClick()
            compose.waitUntil(8_000) { !vm.state.value.scheduleOptionsLoading }
            compose.onNodeWithText("重复规则").assertExists()
            for (label in listOf("每隔几分钟", "每小时", "每天", "每周", "每月", "每年", "高级 Cron")) {
                compose.onNodeWithText(label).performScrollTo().performClick()
            }
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithContentDescription("日程示例操作").performClick()
            compose.onNodeWithText("编辑").performClick()
            compose.waitUntil(8_000) { !vm.state.value.scheduleOptionsLoading }
            compose.onNodeWithText("编辑日程").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
        } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
    }

    @Test fun scheduleEditorUsesEffectiveTimezoneAndSavesReminderOptionsAtTheTopLevel() {
        val saved = AtomicReference("""{"id":"daily","name":"日程示例","command":"生成简报","pattern":"0 9 * * *","enabled":true,"notify_policy":"important","contact_via_channels":true}""")
        UpstreamFixture { request -> when (request.path) {
            "/bots/b/schedule" -> UpstreamReply(body = """{"items":[${saved.get()}]}""")
            "/bots/b/schedule/daily" -> {
                val changes = json.parseToJsonElement(request.body).jsonObject
                saved.set(JsonObject(json.parseToJsonElement(saved.get()).jsonObject + changes).toString())
                UpstreamReply(body = saved.get())
            }
            "/bots/b/settings" -> UpstreamReply(body = """{"effective_timezone":"Asia/Singapore"}""")
            "/bots/b/workdirs", "/bots/b/sessions" -> UpstreamReply(body = """{"items":[]}""")
            "/models" -> UpstreamReply(body = "[]")
            else -> base(request)
        } }.use { server ->
            val vm = BotFeatureViewModel(repository(server))
            compose.setContent { MemohTheme { val state by vm.state.collectAsState(); BotFeatureScreen(state, vm) } }
            try {
                compose.runOnIdle { vm.open("b", BotFeature.Schedules) }
                compose.waitUntil(8000) { vm.state.value.hasLoaded }
                compose.onNodeWithContentDescription("日程示例操作").performClick()
                compose.onNodeWithText("编辑").performClick()
                compose.waitUntil(8000) { !vm.state.value.scheduleOptionsLoading }
                compose.onNodeWithText("Bot 时区 · Asia/Singapore", substring = true).assertExists()
                compose.onNodeWithText("仅重要更新").performScrollTo().performClick()
                compose.onNodeWithText("每次运行后提醒").performClick()
                compose.onNodeWithContentDescription("允许通过已配置的社交平台联系我").performScrollTo().performClick()
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(8000) { vm.state.value.schedules.firstOrNull()?.notifyPolicy == "every_run" }
                val request = server.requests.single { it.method == "PUT" && it.path == "/bots/b/schedule/daily" }
                val body = json.parseToJsonElement(request.body).jsonObject
                assertEquals(JsonPrimitive("every_run"), body["notify_policy"])
                assertEquals(JsonPrimitive(false), body["contact_via_channels"])
                val execution = body["execution"] as? JsonObject
                assertFalse(execution?.containsKey("notify_policy") == true)
                assertFalse(execution?.containsKey("contact_via_channels") == true)
                assertFalse(server.requests.any { it.path == "/users/me" })
            } finally { vm.viewModelScope.cancel() }
        }
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
