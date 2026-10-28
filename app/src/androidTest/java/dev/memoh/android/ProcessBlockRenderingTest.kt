package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import dev.memoh.feature.chat.components.ProcessBlock
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProcessBlockRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val reasoning = UIMessage(4, "reasoning", content = "先检查文件，再运行验证，保留用户已经展开的过程。",
        reasoningTiming = UIReasoningTiming(3500))
    private val read = UIMessage(6, "tool", name = "read", toolCallId = "read-call",
        input = Json.parseToJsonElement("""{"path":"/data/project/Main.kt"}"""),
        output = Json.parseToJsonElement("""{"content":[{"type":"text","text":"fun main() = println(\"Hello\")"}]}"""))
    private val exec = UIMessage(8, "tool", name = "exec", toolCallId = "exec-call", running = true,
        input = Json.parseToJsonElement("""{"description":"运行相关验证","command":"./gradlew testDebugUnitTest"}"""), elapsedTimeSeconds = 3.0)

    @Test fun disclosureChoicesSurviveCompletionAndStateRestoration() {
        var active by mutableStateOf(true)
        var messages by mutableStateOf(listOf(reasoning, read, exec))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MemohTheme { Surface {
            Column(Modifier.fillMaxWidth().padding(20.dp)) { ProcessBlock(messages, active, active) }
        } } }
        compose.onNodeWithText(reasoning.content).assertDoesNotExist()
        compose.onNodeWithText("探索中").performClick()
        compose.onNodeWithText("已思考 4 秒").performClick()
        compose.onNodeWithText(reasoning.content).assertIsDisplayed()
        compose.runOnIdle { active = false; messages = messages.mapIndexed { i, m -> m.copy(id = i, running = false) } }
        compose.onNodeWithText("已探索").assertIsDisplayed()
        compose.onNodeWithText(reasoning.content).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(reasoning.content).assertIsDisplayed()
        screenshot("process-light-expanded")
        compose.onNodeWithText("已探索").performClick()
        compose.onNodeWithText(reasoning.content).assertDoesNotExist()
    }

    @Test fun liveProcessKeepsItsOpenDetailsWhenItMovesIntoRestHistory() {
        val messages = listOf(reasoning, read, exec)
        var state by mutableStateOf(ChatUiState(session = Session("process-session", title = "执行过程"),
            historyLoading = false, awaitingSnapshot = false, hasMoreHistory = false,
            modelCatalogLoading = false, socketStatus = SocketStatus.Connected,
            history = listOf(UITurn("turn-1", role = "user", text = "检查项目并运行验证")),
            runtime = RuntimeState(sessionId = "process-session", run = RuntimeCurrentRunView(runId = "run-1", turnId = "turn-1", messages = messages))))
        compose.setContent { MemohTheme { ChatScreen(state, {}, {}, {}, {}, {},
            onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
            onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        compose.onNodeWithText("探索中").performClick()
        compose.onNodeWithText("读取 Main.kt").performClick()
        compose.onNodeWithText("fun main() = println(\"Hello\")").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(runtime = RuntimeState.EMPTY, history = state.history + UITurn("turn-1", id = "persisted-assistant", role = "assistant",
                messages = messages.mapIndexed { i, m -> m.copy(id = i, running = false) }))
        }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "process-history-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
        screenshot("process-chat-history")
        compose.onNodeWithText("fun main() = println(\"Hello\")").assertIsDisplayed()
    }

    @Test fun darkLargeTextSupportsPerFileDiffsAndNeutralFailedToolRows() {
        val patch = UIMessage(10, "tool", name = "apply_patch", toolCallId = "patch-call", diff =
            "--- a/Main.kt\n+++ b/Main.kt\n@@ -1 +1 @@\n-old value\n+new value\n--- a/Test.kt\n+++ b/Test.kt\n@@ -1 +1 @@\n-old test\n+new test")
        val failure = exec.copy(running = false, output = Json.parseToJsonElement("""{"isError":true,"content":[{"type":"text","text":"permission denied"}]}"""))
        compose.setContent { MemohTheme(darkTheme = true) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) { Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text("执行过程", style = MaterialTheme.typography.titleLarge)
                    ProcessBlock(listOf(patch), false, false)
                    Spacer(Modifier.height(16.dp))
                    ProcessBlock(listOf(failure), false, false)
                }
            } }
        } }
        compose.onNodeWithText("编辑 Main.kt").performClick()
        compose.onNodeWithText("编辑 Test.kt").assertIsDisplayed()
        compose.onNodeWithText("运行相关验证").performClick()
        compose.onNodeWithText("permission denied").assertIsDisplayed()
        screenshot("process-dark-diff-error")
    }

    @Test fun aStreamingThoughtDoesNotOpenOrCloseItself() {
        var active by mutableStateOf(true)
        var messages by mutableStateOf(listOf(reasoning))
        compose.setContent { MemohTheme { Surface { ProcessBlock(messages, active, active) } } }
        compose.onNodeWithText(reasoning.content).assertDoesNotExist()
        compose.onNodeWithText("正在思考如何处理").performClick()
        compose.onNodeWithText(reasoning.content).assertIsDisplayed()
        compose.runOnIdle { messages = listOf(reasoning, read) }
        compose.onNodeWithText(reasoning.content).assertIsDisplayed()
        compose.runOnIdle { active = false }
        compose.onNodeWithText("已思考 4 秒").assertIsDisplayed()
        compose.onNodeWithText(reasoning.content).assertIsDisplayed()
    }

    @Test fun veryLongThoughtsOnlyLayOutVisibleChunksAndCanStillBeClosed() {
        val thought = reasoning.copy(content = (1..8_000).joinToString("\n") {
            "检查条目 $it：检查运行状态、保留工具输出，并逐步验证会话恢复。"
        } + "\n全文末尾")
        compose.setContent { MemohTheme { Surface {
            Column(Modifier.fillMaxSize().padding(20.dp)) { ProcessBlock(listOf(thought), false, false) }
        } } }
        val started = android.os.SystemClock.elapsedRealtime()
        compose.onNodeWithText("已思考 4 秒").performClick()
        compose.onNodeWithContentDescription("思考详情").assertIsDisplayed()
        val bounds = compose.onNodeWithContentDescription("思考详情").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 320.dp)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        val chunks = compose.onAllNodes(hasText("检查条目", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        android.util.Log.i("ChatRegression", "reasoning_chars=${thought.content.length}, opening_ms=$elapsed, composed_chunks=${chunks.size}")
        assertTrue("Long reasoning must stay virtualized; composed ${chunks.size} chunks", chunks.size < 8)
        assertTrue("Opening must not block for seconds: ${elapsed}ms", elapsed < 4_000)
        compose.onNodeWithContentDescription("思考详情").performScrollToNode(hasText("全文末尾", substring = true))
        compose.onNodeWithContentDescription("思考详情").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        compose.onNodeWithText("全文末尾", substring = true).assertIsDisplayed()
        screenshot("long-reasoning-virtualized")
        compose.onNodeWithText("已思考 4 秒").performClick()
        compose.onNodeWithContentDescription("思考详情").assertDoesNotExist()
        compose.onNodeWithText("已思考 4 秒").performClick()
        compose.onNodeWithText("全文末尾", substring = true).assertIsDisplayed()
    }

    @Test fun longThoughtReadingPositionSurvivesClosingItsWholeProcessGroup() {
        val thought = reasoning.copy(content = (1..400).joinToString("\n") { "阅读位置 $it：继续阅读这一段详细推演。" } + "\n阅读终点")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MemohTheme { Surface {
            Column(Modifier.fillMaxSize().padding(20.dp)) { ProcessBlock(listOf(thought, read, exec.copy(running = false)), false, false) }
        } } }
        compose.onNodeWithText("已探索").performClick()
        compose.onNodeWithText("已思考 4 秒").performClick()
        compose.onNodeWithContentDescription("思考详情").performScrollToNode(hasText("阅读终点", substring = true))
        compose.onNodeWithContentDescription("思考详情").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        compose.onNodeWithText("阅读终点", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已探索").performClick()
        compose.onNodeWithContentDescription("思考详情").assertDoesNotExist()
        compose.onNodeWithText("已探索").performClick()
        compose.onNodeWithText("阅读终点", substring = true).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("阅读终点", substring = true).assertIsDisplayed()
    }

    @Test fun restoringAnActiveConversationDoesNotReplayItsLoadingCover() {
        val state = ChatUiState(session = Session("resume-session"), historyLoading = false,
            awaitingSnapshot = false, hasMoreHistory = false, modelCatalogLoading = false,
            socketStatus = SocketStatus.Connected, runtime = RuntimeState(sessionId = "resume-session",
                run = RuntimeCurrentRunView(runId = "run", turnId = "turn", messages = listOf(read, exec))))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MemohTheme { ChatScreen(state, {}, {}, {}, {}, {},
            onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
            onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        compose.onNodeWithText("探索中").performClick()
        compose.onNodeWithText("读取 Main.kt").performClick()
        compose.onNodeWithText("fun main() = println(\"Hello\")").assertIsDisplayed()
        compose.onNodeWithText("正在处理…").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        compose.onNodeWithText("执行中 · 3 秒").assertIsDisplayed()
        compose.onNodeWithText("fun main() = println(\"Hello\")").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        Thread.sleep(350)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
