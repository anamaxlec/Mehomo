package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.designsystem.component.MemohComposerTextField
import dev.memoh.core.model.Session
import dev.memoh.core.model.CommandActionResult
import dev.memoh.core.network.*
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatViewModel
import dev.memoh.feature.chat.ChatSocketFactory
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch

/** Exercise the real ViewModel, HTTP catalogue, WebSocket and composer together. */
@RunWith(AndroidJUnit4::class)
class NativeSkillsLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val catalogue = """{"skills":[{"name":"dataviz","display_name":"图表助手","description":"将数据绘制成图表","source_kind":"user","state":"ready"},{"name":"writing","description":"修改文章"}]}"""

    private fun chat(server: ContinuityFixture): ChatViewModel {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "native-skills-${System.nanoTime()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        val credentials = CredentialStore(context, json)
        credentials.saveAccount(StoredAccount(prefix, server.origin, "self-hosted", username = "Fixture"))
        credentials.saveToken(prefix, "fixture-token", null)
        val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
        val root = File(context.cacheDir, prefix)
        return ChatViewModel(repository, json, ChatSocketFactory(OkHttpClient(), json, CloudAuth(OkHttpClient(), json)),
            SettingsStore(context), ChatHistoryStore(File(root, "history"), json), AttachmentDraftStore(File(root, "drafts"), json))
    }

    private fun show(vm: ChatViewModel) {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent { MemohTheme(darkTheme = false) {
            val state by vm.state.collectAsState()
            ChatSessionLifecycle(vm, state.session?.id)
            ChatScreen(state, {}, vm::send, vm::abort, vm::setDraft, vm::loadOlder,
                onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                onCancelUserInput = { _, _ -> }, onDismissError = vm::clearError,
                onRefreshSlashCatalog = vm::refreshSlashCatalog, onQuickAction = vm::quickAction)
        } }
        compose.runOnIdle { vm.open("b", "s") }
        compose.waitUntil(8_000) { vm.state.value.nativeSkillsEnabled && vm.state.value.canSend && !vm.state.value.historyLoading }
    }

    @Test fun manualEditsPreserveTheCursorAndExternalCompletionMovesItToTheEnd() {
        val text = mutableStateOf("abc def")
        compose.setContent { MemohTheme {
            MemohComposerTextField(text.value, { text.value = it }, "正文", enabled = true)
        } }
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(3))
        compose.onNode(hasSetTextAction()).performTextInput("X")
        compose.runOnIdle { assertEquals("abcX def", text.value); text.value = "/writing " }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.TextSelectionRange, TextRange("/writing ".length)))
        compose.onNode(hasSetTextAction()).performTextInput("正文")
        compose.runOnIdle { assertEquals("/writing 正文", text.value) }
    }

    @Test fun nativeSkillsLoadWithoutRuntimeCommandsOrWaitingForContextAndCanBeSent() {
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            server.controlsBody = """{"commands":[]}"""
            server.skillCatalogBody = catalogue
            val catalog = CountDownLatch(1)
            val slowContext = CountDownLatch(1)
            val slowControls = CountDownLatch(1)
            server.skillCatalogBlock = catalog; server.infoBlock = slowContext; server.controlsBlock = slowControls
            val vm = chat(server)
            try {
                show(vm)
                compose.onNode(hasSetTextAction()).performClick().performTextInput("/")
                compose.onNodeWithContentDescription("正在加载 Skills").assertIsDisplayed()
                compose.waitUntil(5_000) { server.skillCatalogReads.get() == 1 && server.infoReads.get() > 0 }
                catalog.countDown()
                compose.waitUntil(5_000) { vm.state.value.skillCatalog.size == 2 && !vm.state.value.skillCatalogLoading }
                assertNull(vm.state.value.controls)
                assertNull(vm.state.value.sessionInfo)
                compose.onNodeWithText("/dataviz").assertIsDisplayed()
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                File(context.getExternalFilesDir(null), "native-skills-loaded.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                compose.onNode(hasSetTextAction()).performTextReplacement("  /图表")
                compose.onNodeWithText("/dataviz").assertIsDisplayed().performClick()
                compose.runOnIdle { assertEquals("/dataviz ", vm.state.value.draft); assertTrue(server.sentMessages.isEmpty()) }
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.TextSelectionRange, TextRange("/dataviz ".length)))
                compose.onNode(hasSetTextAction()).performTextInput("绘制销售趋势")
                compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
                compose.waitUntil(5_000) { server.sentMessages.size == 1 && vm.state.value.isRunning }
                assertEquals("/dataviz 绘制销售趋势", server.sentMessages.single().text)
                assertEquals(1, server.skillCatalogReads.get())
            } finally {
                catalog.countDown(); slowContext.countDown(); slowControls.countDown()
                compose.runOnIdle { vm.viewModelScope.cancel() }
            }
        }
    }

    @Test fun catalogueFailureAndEmptyResultsRemainVisibleAndRetryLoadsTheSkills() {
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            server.controlsBody = """{"commands":[]}"""
            server.skillCatalogStatus = 503; server.skillCatalogBody = "{}"
            val vm = chat(server)
            try {
                show(vm)
                compose.onNode(hasSetTextAction()).performTextInput("/")
                compose.waitUntil(5_000) { vm.state.value.skillCatalogError != null && !vm.state.value.skillCatalogLoading }
                compose.onNodeWithText("重新加载").assertIsDisplayed()
                assertTrue(vm.state.value.skillCatalog.isEmpty())
                server.skillCatalogStatus = 200; server.skillCatalogBody = """{"skills":[]}"""
                compose.onNodeWithText("重新加载").performClick()
                compose.waitUntil(5_000) { vm.state.value.skillCatalogError == null && !vm.state.value.skillCatalogLoading }
                compose.onNodeWithText("暂无可用的 Skill").assertIsDisplayed()
                server.skillCatalogBody = catalogue
                compose.onNodeWithText("重新加载").performClick()
                compose.waitUntil(5_000) { vm.state.value.skillCatalog.size == 2 }
                compose.onNodeWithText("/dataviz").assertIsDisplayed()
                compose.onNode(hasSetTextAction()).performTextReplacement("/missing")
                compose.onNodeWithText("没有匹配的命令或 Skill").assertIsDisplayed()
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun switchingToADirectAgentKeepsItsProjectCommandsSeparateFromNativeSkills() {
        ContinuityFixture(json).use { server ->
            server.clearHistory(); server.skillCatalogBody = catalogue
            val catalog = CountDownLatch(1)
            server.skillCatalogBlock = catalog
            server.agentControlsBody = """{"commands":[{"name":"review","kind":"turn","skill":true}]}"""
            val vm = chat(server)
            try {
                show(vm)
                compose.waitUntil(5_000) { server.skillCatalogReads.get() == 1 }
                compose.runOnIdle { vm.open("b", "other", Session("other", botId = "b", runtimeType = "claude-code",
                    botAgentId = "claude", workdirId = "project")) }
                compose.waitUntil(5_000) { vm.state.value.canSend && vm.state.value.controls?.commands?.singleOrNull()?.name == "review" }
                catalog.countDown()
                compose.onNode(hasSetTextAction()).performTextInput("/")
                compose.onNodeWithText("/review").assertIsDisplayed()
                compose.onNodeWithText("/dataviz").assertDoesNotExist()
                assertFalse(vm.state.value.nativeSkillsEnabled)
                assertTrue(vm.state.value.skillCatalog.isEmpty())
                assertTrue(server.runtimeControlRequests.any { it.contains("/agents/claude/runtime-controls?workdir_id=project") })
                assertEquals(1, server.skillCatalogReads.get())
            } finally { catalog.countDown(); compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun skillRejectionRestoresTheDraftAndAllowsRetry() {
        ContinuityFixture(json).use { server ->
            server.clearHistory(); server.skillCatalogBody = catalogue
            server.messageFailureCode = "slash.skill_disabled"
            val vm = chat(server)
            try {
                show(vm)
                compose.onNode(hasSetTextAction()).performTextInput("/dataviz 绘制趋势")
                compose.onNodeWithContentDescription("发送").performClick()
                compose.waitUntil(5_000) { vm.state.value.errorProblem?.code == "slash.skill_disabled" }
                assertTrue(vm.state.value.pending.isEmpty())
                assertEquals("/dataviz 绘制趋势", vm.state.value.draft)
                compose.onNodeWithContentDescription("发送").assertIsEnabled()
                server.messageFailureCode = null
                compose.onNodeWithContentDescription("发送").performClick()
                compose.waitUntil(5_000) { server.sentMessages.size == 2 && vm.state.value.isRunning }
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun slashCommandResultsSettleWithoutLeavingASendingTurn() {
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            server.messageCommandResult = CommandActionResult(kind = "skill_list", title = "可用 Skills", text = "dataviz · 绘制图表")
            val vm = chat(server)
            try {
                show(vm)
                compose.onNode(hasSetTextAction()).performTextInput("/help details")
                compose.onNodeWithContentDescription("发送").performClick()
                compose.waitUntil(5_000) { vm.state.value.commandOutput != null }
                assertTrue(vm.state.value.pending.isEmpty())
                assertEquals("", vm.state.value.draft)
                assertFalse(vm.state.value.isRunning)
                compose.onNodeWithText("dataviz · 绘制图表", substring = true).assertIsDisplayed()
                compose.onNode(hasSetTextAction()).performTextInput("接着提问")
                compose.onNodeWithContentDescription("发送").assertIsEnabled()
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun shortcutsAppearBeforeSlowSkillsAndCachedSkillsDoNotRefetchOnEverySlash() {
        ContinuityFixture(json).use { server ->
            server.clearHistory(); server.skillCatalogBody = catalogue
            val catalog = CountDownLatch(1)
            server.skillCatalogBlock = catalog
            val vm = chat(server)
            try {
                show(vm)
                compose.onNode(hasSetTextAction()).performTextInput("/")
                compose.onNodeWithText("快捷操作").assertIsDisplayed()
                compose.onNodeWithText("帮助").assertIsDisplayed()
                compose.onNodeWithText("切换模型").assertIsDisplayed()
                compose.onNodeWithText("帮助").performClick()
                compose.waitUntil(5_000) { server.quickActions.contains("help") && vm.state.value.commandOutput != null }
                assertTrue(server.sentMessages.isEmpty())
                catalog.countDown()
                compose.waitUntil(5_000) { vm.state.value.skillCatalog.size == 2 }
                val reads = server.skillCatalogReads.get()
                repeat(3) {
                    compose.onNode(hasSetTextAction()).performTextReplacement("/")
                    compose.onNode(hasScrollAction() and SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "命令与技能"))
                        .performScrollToNode(hasText("/dataviz"))
                    compose.onNodeWithText("/dataviz").assertIsDisplayed()
                    compose.onNode(hasSetTextAction()).performTextReplacement("")
                }
                assertEquals("Opening / must reuse the prefetched catalogue", reads, server.skillCatalogReads.get())
                compose.onNode(hasSetTextAction()).performTextReplacement("/skill list")
                compose.onNodeWithContentDescription("发送").performClick()
                compose.waitUntil(5_000) { server.quickActions.contains("skill.list") }
                assertTrue(server.sentMessages.isEmpty())
                compose.waitUntil(5_000) { !vm.state.value.busy }
                server.quickActionFailureCode = "slash.permission_denied"
                compose.onNode(hasSetTextAction()).performTextReplacement("/help")
                compose.onNodeWithContentDescription("发送").performClick()
                compose.waitUntil(5_000) { vm.state.value.errorProblem?.code == "slash.permission_denied" }
                assertNull(vm.state.value.notice)
                assertTrue(vm.state.value.pending.isEmpty())
            } finally { catalog.countDown(); compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }
}
