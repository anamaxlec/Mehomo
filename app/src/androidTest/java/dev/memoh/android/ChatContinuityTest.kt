package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.*
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatControlsSheet
import dev.memoh.feature.chat.ChatSocketFactory
import dev.memoh.feature.chat.ChatViewModel
import dev.memoh.feature.sessions.SessionsViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** All traffic and credentials belong to a local fixture, never a user's account. */
@RunWith(AndroidJUnit4::class)
class ChatContinuityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun repository(server: ContinuityFixture): Pair<SessionRepository, Context> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "continuity-${System.nanoTime()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        val credentials = CredentialStore(context, json)
        credentials.saveAccount(StoredAccount(prefix, server.origin, "self-hosted", username = "Fixture"))
        credentials.saveToken(prefix, "fixture-token", null)
        return SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json)) to context
    }

    private fun chat(server: ContinuityFixture): Triple<ChatViewModel, SettingsStore, SessionRepository> {
        val (repository, context) = repository(server)
        val settings = SettingsStore(context)
        val root = File(context.cacheDir, "queue-${System.nanoTime()}")
        return Triple(ChatViewModel(repository, json,
            ChatSocketFactory(OkHttpClient(), json, CloudAuth(OkHttpClient(), json)), settings,
            ChatHistoryStore(File(root, "history"), json), AttachmentDraftStore(File(root, "drafts"), json)), settings, repository)
    }

    @Test fun warmResumeKeepsRetryReadyButAnActualReconnectWaitsForItsSnapshot() {
        ContinuityFixture(json).use { server ->
            val reply = UITurn("turn", id = "persisted-reply", role = "assistant",
                messages = listOf(UIMessage(0, "text", "保留已经完成的回复")))
            server.messagesBody = json.encodeToString(ListResponse(items = listOf(reply)))
            server.finishRun(reply.safeMessages)
            val (vm, _, _) = chat(server)
            val warmSnapshot = CountDownLatch(1)
            val reconnectSnapshot = CountDownLatch(1)
            try {
                compose.setContent { MemohTheme {
                    val state by vm.state.collectAsState()
                    ChatSessionLifecycle(vm, state.session?.id)
                    ChatScreen(state, {}, {}, {}, vm::setDraft, vm::loadOlder, onRegenerate = vm::regenerate,
                        onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                        onCancelUserInput = { _, _ -> }, onDismissError = {})
                } }
                compose.runOnIdle { vm.open("b", "s") }
                compose.waitUntil(5_000) { vm.state.value.retryableTurnId == "turn" }
                compose.onNodeWithContentDescription("重新生成").assertIsEnabled()
                compose.runOnIdle { vm.setDraft("保留草稿") }
                val subscriptions = server.subscriptionReads.get()
                val baselineSeq = vm.state.value.runtime.seq
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                server.snapshotBlock = warmSnapshot
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(5_000) { server.subscriptionReads.get() > subscriptions }
                assertEquals("Warm refresh must keep the validated reply usable", "turn", vm.state.value.retryableTurnId)
                assertFalse(vm.state.value.awaitingSnapshot)
                compose.onNodeWithContentDescription("重新生成").assertIsEnabled()
                assertEquals(1, server.connections.get())
                warmSnapshot.countDown()
                compose.waitUntil(5_000) { vm.state.value.runtime.seq > baselineSeq }
                server.snapshotBlock = reconnectSnapshot
                server.disconnectRuntime()
                compose.waitUntil(5_000) { server.connections.get() == 2 &&
                    server.subscriptionReads.get() > subscriptions + 1 && vm.state.value.socketStatus == SocketStatus.Connected }
                assertTrue("A new transport must wait for its authoritative snapshot", vm.state.value.awaitingSnapshot)
                assertNull(vm.state.value.retryableTurnId)
                compose.onNodeWithContentDescription("重新生成").assertIsNotEnabled()
                compose.onNodeWithText(reply.safeMessages.single().content).assertIsDisplayed()
                reconnectSnapshot.countDown()
                compose.waitUntil(5_000) { vm.state.value.retryableTurnId == "turn" }
                compose.onNodeWithContentDescription("重新生成").assertIsEnabled().performClick()
                compose.waitUntil(5_000) { server.retryRequests.get() == 1 }
                assertEquals("保留草稿", vm.state.value.draft)
                compose.onNodeWithText(reply.safeMessages.single().content).assertIsDisplayed()
            } finally {
                warmSnapshot.countDown(); reconnectSnapshot.countDown()
                compose.runOnIdle { vm.viewModelScope.cancel() }
            }
        }
    }

    @Test fun firstReplyAndForegroundResumeKeepKnownHistoryAndAvoidRepeatedSubscriptions() {
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            server.modelsBody = """[{"id":"first-model","type":"chat"},{"id":"second-model","type":"chat"}]"""
            val (vm, _, _) = chat(server)
            try {
                compose.setContent { MemohTheme {
                    val state by vm.state.collectAsState()
                    ChatSessionLifecycle(vm, state.session?.id)
                    ChatScreen(state, {}, vm::send, vm::abort, vm::setDraft, vm::loadOlder,
                        onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                        onCancelUserInput = { _, _ -> }, onDismissError = {})
                } }
                compose.runOnIdle { vm.open("b", "s", Session("s", botId = "b", preferredChatModelId = "first-model")) }
                compose.waitUntil(5_000) { vm.state.value.canSend && !vm.state.value.historyLoading && !vm.state.value.modelCatalogLoading }
                assertFalse(vm.state.value.hasMoreHistory)
                val subscriptions = server.subscriptionReads.get()
                val infoReads = server.infoReads.get()
                compose.onNode(hasSetTextAction()).performTextInput("第一条消息")
                compose.onNodeWithContentDescription("发送").performClick()
                compose.waitUntil(5_000) { vm.state.value.isRunning && server.infoReads.get() > infoReads }
                assertEquals("Starting a task must not subscribe again", subscriptions, server.subscriptionReads.get())
                val user = UITurn("turn", id = "first-user", role = "user", text = "第一条消息")
                val reply = UITurn("turn", id = "first-assistant", role = "assistant",
                    messages = listOf(UIMessage(0, "text", "第一条回复")))
                server.messagesBody = json.encodeToString(ListResponse(items = listOf(user, reply)))
                server.finishRun(reply.safeMessages)
                compose.waitUntil(5_000) { !vm.state.value.isRunning && vm.state.value.history.lastOrNull()?.id == reply.id }
                assertFalse("A first reply cannot create older history", vm.state.value.hasMoreHistory)
                compose.onNodeWithText("第一条回复").assertIsDisplayed()
                compose.onNodeWithContentDescription("正在加载更早消息").assertDoesNotExist()
                assertEquals(0, server.olderHistoryReads.get())
                compose.runOnIdle { vm.selectModel("second-model") }
                compose.waitUntil(5_000) { vm.state.value.selectedModelId == "second-model" && !vm.state.value.busy }
                assertEquals("Model changes must not subscribe again", subscriptions, server.subscriptionReads.get())
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(5_000) { server.subscriptionReads.get() == subscriptions + 1 && vm.state.value.canSend }
                assertEquals(1, server.connections.get())
                assertEquals(0, server.olderHistoryReads.get())
                compose.onNodeWithText("第一条回复").assertIsDisplayed()
                compose.onNodeWithText("已连接").assertDoesNotExist()
                compose.onNodeWithText("正在恢复连接…").assertDoesNotExist()
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun uncachedHistoryLoadsBeforeSlowSessionDetailsAndSurvivesTheirArrival() {
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            val details = CountDownLatch(1)
            val snapshot = CountDownLatch(1)
            server.sessionBlock = details
            server.snapshotBlock = snapshot
            val expected = listOf(UITurn(turnId = "persisted", role = "user", text = "无需等待会话信息的历史内容"))
            server.messagesBody = json.encodeToString(ListResponse(items = expected))
            val (vm, _, _) = chat(server)
            try {
                compose.setContent { MemohTheme {
                    val state by vm.state.collectAsState()
                    ChatScreen(state, {}, {}, {}, vm::setDraft, {},
                        onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                        onCancelUserInput = { _, _ -> }, onDismissError = {})
                } }
                compose.runOnIdle { vm.open("b", "s") }
                compose.waitUntil(3_000) { server.historyReads.get() > 0 && !vm.state.value.historyLoading }
                assertEquals(expected, vm.state.value.history)
                assertFalse(vm.state.value.canSend)
                assertFalse(vm.state.value.canEditDraft)
                compose.onNodeWithText("无需等待会话信息的历史内容").assertIsDisplayed()
                compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
                snapshot.countDown()
                compose.waitUntil(3_000) { !vm.state.value.awaitingSnapshot && !vm.state.value.draftRestoring }
                assertFalse("An address-only session must not send before metadata resolves", vm.state.value.canSend)
                assertFalse(vm.state.value.canEditDraft)
                details.countDown()
                compose.waitUntil(3_000) { !vm.state.value.modelCatalogLoading }
                assertEquals("Session metadata must not reset an already loaded page", expected, vm.state.value.history)
                assertEquals("Opening must issue one initial history request", 1, server.historyReads.get())
                compose.waitUntil(3_000) { vm.state.value.canSend }
            } finally {
                details.countDown(); snapshot.countDown()
                compose.runOnIdle { vm.viewModelScope.cancel() }
            }
        }
    }

    @Test fun cachedEmptyConversationIsReadyBeforeSlowRefreshAndReusesBotOptions() {
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            server.modelsBody = """[{"id":"chosen-model","type":"chat","display_name":"Fixture Model"}]"""
            server.workdirsBody = """{"workdirs":[{"id":"project","name":"Fixture Project"}]}"""
            val gate = CountDownLatch(1)
            server.sessionBlock = gate
            val (repository, context) = repository(server)
            val account = repository.state.value.account!!
            val root = File(context.cacheDir, "prepared-chat-${System.nanoTime()}")
            val store = ChatHistoryStore(File(root, "history"), json)
            val cached = Session("s", botId = "b", runtimeType = "model", preferredChatModelId = "chosen-model")
            runBlocking {
                store.save(account.accountId, "", "b", cached, emptyList())
                store.save(account.accountId, "", "b", cached.copy(id = "other"), emptyList())
            }
            val vm = ChatViewModel(repository, json,
                ChatSocketFactory(OkHttpClient(), json, CloudAuth(OkHttpClient(), json)), SettingsStore(context),
                store, AttachmentDraftStore(File(root, "drafts"), json))
            var prepared = false
            var secondPrepared = false
            val secondGate = CountDownLatch(1)
            try {
                compose.runOnIdle { vm.viewModelScope.launch { vm.prepareOpen("b", "s"); prepared = true } }
                compose.waitUntil(5_000) { prepared && server.sessionReads.get() > 0 }
                assertEquals("s", vm.state.value.session?.id)
                assertTrue(vm.state.value.hasResolvedHistory)
                assertTrue(vm.state.value.history.isEmpty())
                assertEquals("chosen-model", vm.state.value.selectedModelId)
                compose.setContent { MemohTheme {
                    val state by vm.state.collectAsState()
                    ChatScreen(state, {}, {}, {}, vm::setDraft, {},
                        onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                        onCancelUserInput = { _, _ -> }, onDismissError = {})
                } }
                compose.onNodeWithText("开始对话").assertIsDisplayed()
                compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
                compose.onNodeWithContentDescription("模型选项加载中").assertIsDisplayed()
                gate.countDown()
                compose.waitUntil(5_000) { !vm.state.value.modelCatalogLoading && vm.state.value.workdirs.isNotEmpty() }
                val suggestions = vm.state.value.starterPrompts
                server.sessionBlock = secondGate
                server.modelsBlock = secondGate
                compose.runOnIdle { vm.viewModelScope.launch { vm.prepareOpen("b", "other"); secondPrepared = true } }
                compose.waitUntil(5_000) { secondPrepared }
                assertEquals("other", vm.state.value.session?.id)
                assertTrue(vm.state.value.hasResolvedHistory)
                assertTrue(vm.state.value.modelCatalogLoading)
                assertEquals("Fixture Model", vm.state.value.modelLabel)
                assertEquals("Fixture Project", vm.state.value.workdirs.single().name)
                assertEquals(suggestions, vm.state.value.starterPrompts)
                compose.onNodeWithText("开始对话").assertIsDisplayed()
                compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
                compose.onNodeWithContentDescription("模型：Fixture Model").assertIsDisplayed()
                secondGate.countDown()
                compose.waitUntil(5_000) { !vm.state.value.modelCatalogLoading }
                assertEquals("Fixture Model", vm.state.value.modelLabel)
                assertEquals("Fixture Project", vm.state.value.workdirs.single().name)
            } finally {
                gate.countDown(); secondGate.countDown()
                compose.runOnIdle { vm.viewModelScope.cancel() }
            }
        }
    }

    @Test fun createdConversationShowsSuggestionsBeforeHistoryAndDoesNotRestartOnNavigation() {
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            val historyReady = CountDownLatch(1)
            server.historyBlock = historyReady
            server.capabilitiesBlock = historyReady
            val (vm, _, _) = chat(server)
            try {
                compose.runOnIdle {
                    val created = Session("s", botId = "b", preferredChatModelId = "chosen-model", workdirId = "project")
                    vm.open("b", "s", created)
                    assertEquals(created, vm.state.value.session)
                    assertTrue(vm.state.value.hasResolvedHistory)
                    assertEquals("chosen-model", vm.state.value.selectedModelId)
                    assertEquals("project", vm.state.value.selectedWorkdirId)
                    // The destination's open effect must reuse the preparation done before navigation.
                    vm.open("b", "s")
                    assertTrue(vm.state.value.hasResolvedHistory)
                }
                compose.setContent { MemohTheme {
                    val state by vm.state.collectAsState()
                    ChatScreen(state, {}, {}, {}, vm::setDraft, {},
                        onRefreshSuggestions = vm::refreshSuggestions,
                        onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                        onCancelUserInput = { _, _ -> }, onDismissError = {})
                } }
                compose.waitUntil(5_000) { server.historyReads.get() > 0 && server.capabilityReads.get() > 0 }
                compose.onNodeWithText("开始对话").assertIsDisplayed()
                compose.onNodeWithContentDescription("正在加载建议").assertDoesNotExist()
                val chosen = vm.state.value.starterPrompts.items.first()
                compose.onNodeWithText(chosen.title).assertIsDisplayed().performClick()
                compose.runOnIdle { assertEquals(chosen.prompt, vm.state.value.draft) }
                repeat(4) {
                    val previous = vm.state.value.starterPrompts.items
                    compose.onNodeWithText("换一组").assertIsEnabled().performClick()
                    compose.runOnIdle {
                        val next = vm.state.value.starterPrompts.items
                        assertEquals(3, next.size)
                        assertTrue(next.none { it in previous })
                        assertEquals(chosen.prompt, vm.state.value.draft)
                        assertNull(vm.state.value.suggestionsError)
                    }
                }
                assertEquals(0, server.starterPromptReads.get())
                compose.onNodeWithText("暂时没有新的推荐，可以稍后再试。").assertDoesNotExist()
                compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
                assertEquals(0, server.sessionReads.get())
                historyReady.countDown()
                compose.waitUntil(5_000) { !vm.state.value.historyLoading }
                compose.onNodeWithText("开始对话").assertIsDisplayed()
                compose.onNodeWithContentDescription("正在加载建议").assertDoesNotExist()
            } finally {
                historyReady.countDown()
                compose.runOnIdle { vm.viewModelScope.cancel() }
            }
        }
    }

    @Test fun clearingHistoryWithANewRuntimeEpochRemovesTheOldRunAndTranscript() {
        ContinuityFixture(json).use { server ->
            val (vm, _, _) = chat(server)
            try {
                compose.runOnIdle { vm.open("b", "s") }
                compose.waitUntil(8_000) { vm.state.value.isRunning }
                server.finishRun()
                compose.waitUntil(8_000) { !vm.state.value.isRunning && vm.state.value.history.isNotEmpty() }
                server.clearHistory()
                compose.waitUntil(8_000) { vm.state.value.runtime.epoch == "history-reset" && !vm.state.value.historyLoading && vm.state.value.history.isEmpty() }
                assertNull(vm.state.value.runtime.run)
                assertTrue(vm.state.value.liveMessages.isEmpty())
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun runningMessagesUseQueuesAndOnlySuccessfulSubmissionsClearTheStoredDraft() {
        ContinuityFixture(json).use { server ->
            val (vm, settings, repository) = chat(server)
            val accountId = repository.state.value.account!!.accountId
            try {
                compose.runOnIdle { vm.open("b", "s") }
                compose.waitUntil(8_000) { vm.state.value.canSend && vm.state.value.isRunning &&
                    vm.state.value.controls?.commands?.singleOrNull()?.kind == "read" }
                server.queueFailure = true
                compose.runOnIdle { vm.setDraft("keep on failure"); vm.send(vm.state.value.draft) }
                compose.waitUntil(8_000) { !vm.state.value.busy && vm.state.value.error != null }
                assertEquals("keep on failure", vm.state.value.draft)
                assertTrue(server.enqueued.isEmpty())
                server.queueFailure = false
                compose.runOnIdle { vm.send(vm.state.value.draft) }
                compose.waitUntil(8_000) { !vm.state.value.busy && vm.state.value.queue.followUp.isNotEmpty() }
                assertEquals("", vm.state.value.draft)
                compose.waitUntil(8_000) { runBlocking { settings.draft(accountId, "b", "s") == null } }
                compose.runOnIdle { vm.open("b", "other") }
                compose.waitUntil(8_000) { vm.state.value.session?.id == "other" }
                compose.runOnIdle { vm.open("b", "s") }
                compose.waitUntil(8_000) { vm.state.value.canSend }
                assertEquals("", vm.state.value.draft)
                compose.runOnIdle { vm.setDraft("/steer interrupt now"); vm.send(vm.state.value.draft) }
                compose.waitUntil(8_000) { !vm.state.value.busy && server.enqueued.size == 2 }
                compose.runOnIdle { vm.setDraft("/queue run later"); vm.send(vm.state.value.draft) }
                compose.waitUntil(8_000) { !vm.state.value.busy && server.enqueued.size == 3 }
                assertEquals(listOf(false to "keep on failure", true to "interrupt now", false to "run later"), server.enqueued.toList())
                assertEquals("Running submissions must not start a second WebSocket run", 0, server.messageRequests.get())
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun queuePanelKeepsItsOwnTextAfterFailureAndClearsOnlyAfterSuccess() {
        ContinuityFixture(json).use { server ->
            val (vm, _, _) = chat(server)
            compose.setContent { MemohTheme {
                val state by vm.state.collectAsState()
                ChatControlsSheet(state, vm) {}
            } }
            try {
                compose.runOnIdle { vm.open("b", "s") }
                compose.waitUntil(8_000) { vm.state.value.canSend && vm.state.value.queue.steerSupported }
                server.queueFailure = true
                compose.onNode(hasSetTextAction()).performTextInput("FAIL_PANEL")
                compose.onNodeWithText("加入后续").performClick()
                compose.waitUntil(8_000) { !vm.state.value.busy && vm.state.value.error != null }
                compose.onNode(hasSetTextAction()).assertTextEquals("FAIL_PANEL")
                server.queueFailure = false
                compose.onNodeWithText("加入后续").performClick()
                compose.waitUntil(8_000) { !vm.state.value.busy && vm.state.value.queue.followUp.isNotEmpty() }
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                assertEquals(listOf(false to "FAIL_PANEL"), server.enqueued.toList())
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun successfulQueueRequestCannotEraseANewerDraft() {
        ContinuityFixture(json).use { server ->
            val (vm, settings, repository) = chat(server)
            compose.setContent { MemohTheme {
                val state by vm.state.collectAsState()
                ChatScreen(state, {}, vm::send, vm::abort, vm::setDraft, {},
                    onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                    onCancelUserInput = { _, _ -> }, onDismissError = vm::clearError)
            } }
            try {
                compose.runOnIdle { vm.open("b", "s") }
                compose.waitUntil(8_000) { vm.state.value.canSend && vm.state.value.isRunning }
                server.queueBlock = CountDownLatch(1)
                compose.runOnIdle { vm.setDraft("submitted text"); vm.send(vm.state.value.draft) }
                compose.waitUntil(8_000) { server.queueRequests.get() == 1 }
                compose.onNode(hasSetTextAction()).performTextReplacement("newer draft")
                server.queueBlock!!.countDown()
                compose.waitUntil(8_000) { !vm.state.value.busy && server.enqueued.size == 1 }
                assertEquals("newer draft", vm.state.value.draft)
                compose.waitUntil(8_000) {
                    runBlocking { settings.draft(repository.state.value.account!!.accountId, "b", "s") == "newer draft" }
                }
            } finally {
                server.queueBlock?.countDown()
                compose.runOnIdle { vm.viewModelScope.cancel() }
            }
        }
    }

    @Test fun repeatedReentryKeepsTheRunAndToolsWhileContextRefreshesWithoutAClick() {
        ContinuityFixture(json).use { server ->
            val (repository, context) = repository(server)
            val root = File(context.cacheDir, "continuity-${System.nanoTime()}")
            lateinit var vm: ChatViewModel
            var vmCreated = false
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    vm = ChatViewModel(repository, json, ChatSocketFactory(OkHttpClient(), json, CloudAuth(OkHttpClient(), json)),
                        SettingsStore(context), ChatHistoryStore(File(root, "history"), json), AttachmentDraftStore(File(root, "drafts"), json))
                    vmCreated = true
                    @Suppress("UNCHECKED_CAST")
                    return vm as T
                }
            }
            compose.setContent { MemohTheme {
                val navigation = rememberNavController()
                NavHost(navigation, startDestination = "home") {
                    composable("home") { TextButton({ navigation.navigate("chat") }) { Text("打开测试会话") } }
                    composable("chat") { entry ->
                        val owner = remember(entry) { navigation.getBackStackEntry("home") }
                        val chat = remember(owner) { ViewModelProvider(owner, factory)[ChatViewModel::class.java] }
                        val state by chat.state.collectAsState()
                        val lifecycle = LocalLifecycleOwner.current.lifecycle
                        LaunchedEffect(chat) { chat.open("b", "s") }
                        LaunchedEffect(chat, state.session?.id) {
                            if (state.session != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { chat.observeSession() }
                        }
                        ChatScreen(state, { navigation.popBackStack() }, {}, {}, {}, {},
                            onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                            onCancelUserInput = { _, _ -> }, onDismissError = {})
                    }
                }
            } }
            compose.onNodeWithText("打开测试会话").performClick()
            try {
                compose.waitUntil(8_000) { vmCreated && vm.state.value.isRunning && !vm.state.value.historyLoading }
                compose.onNodeWithText("运行中").assertIsDisplayed()
                compose.onNodeWithText("正在处理…").assertDoesNotExist()
                repeat(3) {
                    compose.onNodeWithText("探索中").performClick()
                    compose.onNodeWithText("读取 Main.kt").performClick()
                    compose.onNodeWithText("fixture tool output").assertIsDisplayed()
                    compose.onNodeWithContentDescription("返回").performClick()
                    compose.onNodeWithText("打开测试会话").performClick()
                    compose.onNodeWithText("运行中").assertIsDisplayed()
                }
                assertEquals("Reentry must reuse the active transport", 1, server.connections.get())
                server.tokens.set(21_000)
                compose.waitUntil(8_000) { vm.state.value.sessionInfo?.contextUsage?.tokens == 21_000L }
                compose.onNodeWithContentDescription("会话信息，上下文已用 21%").assertIsDisplayed()
                compose.onNodeWithContentDescription("上下文检查器").assertDoesNotExist()
                val receivedRun = vm.state.value.runtime
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                val backgroundReads = server.infoReads.get()
                server.advanceInBackground()
                Thread.sleep(6_000)
                assertEquals("Context polling pauses in the background", backgroundReads, server.infoReads.get())
                assertTrue(vm.state.value.runtime.seq > receivedRun.seq)
                compose.mainClock.autoAdvance = false
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                repeat(4) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
                }
                compose.mainClock.autoAdvance = true
                compose.onNodeWithText("运行中").assertIsDisplayed()
                compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
                compose.onNodeWithText("探索中").performClick()
                compose.onNodeWithText("读取 Main.kt").performClick()
                compose.onNodeWithText("background tool output").assertIsDisplayed()
                assertEquals(1, server.connections.get())
            } finally { if (vmCreated) compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun automaticHomeSyncKeepsRunBadgesAndOnlyManualRefreshRaisesTheIndicator() {
        ContinuityFixture(json).use { server ->
            val (repository, context) = repository(server)
            val vm = SessionsViewModel(repository, SettingsStore(context))
            try {
                compose.runOnIdle { vm.start() }
                compose.waitUntil(8_000) { vm.state.value.initialized && !vm.state.value.loading }
                assertNull(vm.state.value.error)
                assertEquals(listOf("s"), vm.state.value.sessions.map { it.id })
                compose.runOnIdle { vm.observeRuns() }
                compose.waitUntil(8_000) { vm.state.value.runStates["s"]?.isRunning == true }
                compose.runOnIdle {
                    vm.stopObservingRuns()
                    assertTrue(vm.state.value.runStates["s"]!!.isRunning)
                    vm.sync()
                    assertTrue(vm.state.value.loading)
                    assertFalse(vm.state.value.refreshing)
                }
                compose.waitUntil(8_000) { !vm.state.value.loading }
                compose.runOnIdle {
                    vm.observeRuns()
                    assertTrue(vm.state.value.runStates["s"]!!.isRunning)
                    vm.refresh()
                    assertTrue(vm.state.value.refreshing)
                }
                compose.waitUntil(8_000) { !vm.state.value.loading }
                assertFalse(vm.state.value.refreshing)
            } finally { compose.runOnIdle { vm.stopObservingRuns(); vm.viewModelScope.cancel() } }
        }
    }
}

private class ContinuityFixture(private val json: Json) : AutoCloseable {
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val clients = CopyOnWriteArrayList<Socket>()
    val origin = "http://localhost:${server.localPort}"
    val connections = AtomicInteger()
    val infoReads = AtomicInteger()
    val tokens = AtomicLong(10_000)
    val messageRequests = AtomicInteger()
    val retryRequests = AtomicInteger()
    val historyReads = AtomicInteger()
    val olderHistoryReads = AtomicInteger()
    val subscriptionReads = AtomicInteger()
    val sessionReads = AtomicInteger()
    val starterPromptReads = AtomicInteger()
    val capabilityReads = AtomicInteger()
    @Volatile var historyBlock: CountDownLatch? = null
    @Volatile var capabilitiesBlock: CountDownLatch? = null
    @Volatile var sessionBlock: CountDownLatch? = null
    @Volatile var modelsBlock: CountDownLatch? = null
    @Volatile var snapshotBlock: CountDownLatch? = null
    @Volatile var modelsBody = "[]"
    @Volatile var messagesBody = """{"items":[]}"""
    @Volatile var workdirsBody = """{"workdirs":[]}"""
    val enqueued = CopyOnWriteArrayList<Pair<Boolean, String>>()
    val queueRequests = AtomicInteger()
    @Volatile var queueFailure = false
    @Volatile var queueBlock: CountDownLatch? = null
    private val sequence = AtomicLong()
    private val subscribers = CopyOnWriteArrayList<Pair<Socket, String>>()
    @Volatile private var run = RuntimeCurrentRunView(runId = "run", turnId = "turn", messages = listOf(
        UIMessage(1, "tool", name = "read", toolCallId = "read", input = buildJsonObject { put("path", "/data/Main.kt") },
            output = JsonPrimitive("fixture tool output")),
        UIMessage(2, "tool", name = "exec", toolCallId = "exec", running = true,
            input = buildJsonObject { put("command", "fixture check"); put("description", "运行相关验证") })))
    private val worker = Thread {
        while (!server.isClosed) try {
            val client = server.accept().also(clients::add)
            Thread { client.use { handle(it) } }.apply { isDaemon = true; start() }
        } catch (_: Exception) { if (server.isClosed) break }
    }.apply { isDaemon = true; start() }

    private fun handle(client: Socket) {
        try {
            val input = DataInputStream(client.getInputStream())
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) header.append(input.readUnsignedByte().toChar())
            val lines = header.toString().split("\r\n")
            val requestTarget = lines.first().split(' ')[1]
            val path = requestTarget.substringBefore('?').removePrefix("/api")
            val method = lines.first().substringBefore(' ')
            val contentLength = lines.firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toInt() ?: 0
            val requestBody = ByteArray(contentLength).also(input::readFully).toString(Charsets.UTF_8)
            val output = client.getOutputStream()
            if (path.endsWith("/web/ws")) {
                val key = lines.first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                connections.incrementAndGet()
                while (!client.isClosed) {
                    val opcode = input.readUnsignedByte() and 15
                    val size = input.readUnsignedByte()
                    val length = when (size and 127) { 126 -> input.readUnsignedShort(); 127 -> input.readLong().toInt(); else -> size and 127 }
                    val mask = ByteArray(if (size and 128 != 0) 4 else 0).also(input::readFully)
                    val bytes = ByteArray(length).also(input::readFully)
                    if (opcode == 8) return
                    if (opcode != 1) continue
                    if (mask.isNotEmpty()) bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
                    val frame = json.decodeFromString<WSClientMessage>(bytes.toString(Charsets.UTF_8))
                    if (frame.type == WSClientMessage.RETRY_MESSAGE) retryRequests.incrementAndGet()
                    if (frame.type == WSClientMessage.MESSAGE) {
                        messageRequests.incrementAndGet()
                        if (historyCleared) {
                            historyCleared = false
                            val user = UIUserTurn("turn", id = "first-user", text = frame.text.orEmpty())
                            run = run.copy(invocationId = frame.invocationId, requestUserTurn = user,
                                userTurns = listOf(user), status = RunStatus.RUNNING)
                            sendEvent(client, UIStreamEvent(type = UIStreamEvent.RUN_ACCEPTED,
                                invocationId = frame.invocationId, sessionId = frame.sessionId, turnId = run.turnId))
                            snapshot(client, frame.sessionId ?: "s")
                        }
                    }
                    if (frame.type == WSClientMessage.RUNTIME_SUBSCRIBE) {
                        val id = frame.sessionId ?: continue
                        subscriptionReads.incrementAndGet()
                        subscribers.addIfAbsent(client to id)
                        snapshotBlock?.await(15, TimeUnit.SECONDS)
                        snapshot(client, id)
                    }
                }
                return
            }
            val session = """{"id":"s","bot_id":"b","title":"连续任务","type":"chat","channel_type":"local","runtime_type":"model"}"""
            val bot = """{"id":"b","name":"Fixture","current_user_permissions":["chat","manage"]}"""
            if (method == "POST" && (path.endsWith("/follow-up-queue") || path.endsWith("/steer-queue"))) {
                queueRequests.incrementAndGet()
                queueBlock?.await(8, TimeUnit.SECONDS)
                if (queueFailure) {
                    val body = """{"code":"agent.provider_overloaded"}""".toByteArray()
                    output.write("HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body); output.flush(); return
                }
                val text = json.parseToJsonElement(requestBody).jsonObject["text"]!!.jsonPrimitive.content
                enqueued.add(path.endsWith("/steer-queue") to text)
            }
            if (path == "/bots/b/messages") {
                historyReads.incrementAndGet()
                if (requestTarget.contains("before_message_id=")) olderHistoryReads.incrementAndGet()
                historyBlock?.await(10, TimeUnit.SECONDS)
            }
            if (path.contains("/starter-prompts")) starterPromptReads.incrementAndGet()
            if (path == "/bots/b/settings") {
                capabilityReads.incrementAndGet()
                capabilitiesBlock?.await(10, TimeUnit.SECONDS)
            }
            if (path == "/bots/b/sessions/s" || path == "/bots/b/sessions/other") {
                sessionReads.incrementAndGet()
                sessionBlock?.await(15, TimeUnit.SECONDS)
            }
            if (path == "/models") modelsBlock?.await(15, TimeUnit.SECONDS)
            val body = when (path) {
                "/bots" -> """{"items":[$bot]}"""
                "/bots/b" -> bot
                "/bots/b/sessions" -> """{"items":[$session]}"""
                "/bots/b/sessions/s" -> session
                "/bots/b/sessions/other" -> session.replace("\"s\"", "\"other\"")
                "/bots/b/sessions/s/runtime-controls" -> """{"session_id":"s","commands":[{"name":"status","kind":"read"}]}"""
                "/bots/b/sessions/s/queue" -> json.encodeToString(SessionQueueResponse(steerSupported = true,
                    followUp = enqueued.mapIndexedNotNull { index, (steer, text) -> if (!steer) QueueItem("q-$index", text = text) else null },
                    steer = enqueued.mapIndexedNotNull { index, (steer, text) -> if (steer) QueueItem("q-$index", text = text) else null }))
                "/bots/b/messages" -> messagesBody
                "/bots/b/agents" -> """{"items":[]}"""
                "/bots/b/sessions/s/status" -> {
                    infoReads.incrementAndGet()
                    """{"context_usage":{"used_tokens":${tokens.get()},"context_window":100000}}"""
                }
                "/models" -> modelsBody
                "/providers" -> "[]"
                "/bots/b/workdirs" -> workdirsBody
                "/bots/b/workspace-targets" -> """{"targets":[]}"""
                else -> "{}"
            }.toByteArray()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body); output.flush()
        } catch (_: java.io.IOException) { /* A cancelled test subscription closes its client. */ }
    }

    @Volatile private var historyCleared = false
    @Volatile private var epoch = "fixture"

    fun finishRun(messages: List<UIMessage> = run.safeMessages) {
        run = run.copy(status = RunStatus.COMPLETED, messages = messages)
        subscribers.forEach { (client, id) -> if (!client.isClosed) snapshot(client, id) }
    }

    fun disconnectRuntime() {
        subscribers.map { it.first }.distinct().forEach { it.close() }
    }

    fun clearHistory() {
        historyCleared = true; epoch = "history-reset"
        subscribers.forEach { (client, id) -> if (!client.isClosed) snapshot(client, id) }
    }

    private fun snapshot(client: Socket, sessionId: String) = synchronized(client) {
        val seq = if (historyCleared) 0L else sequence.incrementAndGet()
        val event = UIStreamEvent(type = UIStreamEvent.RUNTIME_SNAPSHOT, sessionId = sessionId, epoch = epoch, seq = seq,
            snapshot = RuntimeSnapshot(sessionId = sessionId, epoch = epoch, seq = seq, currentRunView = if (historyCleared) null else run))
        sendEvent(client, event)
    }

    private fun sendEvent(client: Socket, event: UIStreamEvent) = synchronized(client) {
        val payload = json.encodeToString(event).toByteArray()
        val output = client.getOutputStream()
        output.write(129)
        if (payload.size < 126) output.write(payload.size) else {
            output.write(126); output.write(payload.size shr 8); output.write(payload.size and 255)
        }
        output.write(payload); output.flush()
    }

    fun advanceInBackground() {
        run = run.copy(messages = run.safeMessages.map {
            if (it.id == 1) it.copy(output = JsonPrimitive("background tool output")) else it
        })
        subscribers.forEach { (client, id) -> if (!client.isClosed) snapshot(client, id) }
    }

    override fun close() { server.close(); clients.forEach { it.close() }; worker.join(1_000) }
}
