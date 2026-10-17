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
            val path = lines.first().split(' ')[1].substringBefore('?').removePrefix("/api")
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
                    if (frame.type == WSClientMessage.MESSAGE) messageRequests.incrementAndGet()
                    if (frame.type == WSClientMessage.RUNTIME_SUBSCRIBE) {
                        val id = frame.sessionId ?: continue
                        subscribers.add(client to id)
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
                "/bots/b/messages", "/bots/b/agents" -> """{"items":[]}"""
                "/bots/b/sessions/s/status" -> {
                    infoReads.incrementAndGet()
                    """{"context_usage":{"used_tokens":${tokens.get()},"context_window":100000}}"""
                }
                "/models", "/providers" -> "[]"
                "/bots/b/workdirs" -> """{"workdirs":[]}"""
                "/bots/b/workspace-targets" -> """{"targets":[]}"""
                else -> "{}"
            }.toByteArray()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body); output.flush()
        } catch (_: java.io.IOException) { /* A cancelled test subscription closes its client. */ }
    }

    @Volatile private var historyCleared = false
    @Volatile private var epoch = "fixture"

    fun finishRun() {
        run = run.copy(status = RunStatus.COMPLETED)
        subscribers.forEach { (client, id) -> if (!client.isClosed) snapshot(client, id) }
    }

    fun clearHistory() {
        historyCleared = true; epoch = "history-reset"
        subscribers.forEach { (client, id) -> if (!client.isClosed) snapshot(client, id) }
    }

    private fun snapshot(client: Socket, sessionId: String) = synchronized(client) {
        val seq = if (historyCleared) 0L else sequence.incrementAndGet()
        val event = UIStreamEvent(type = UIStreamEvent.RUNTIME_SNAPSHOT, sessionId = sessionId, epoch = epoch, seq = seq,
            snapshot = RuntimeSnapshot(sessionId = sessionId, epoch = epoch, seq = seq, currentRunView = if (historyCleared) null else run))
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
