package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.designsystem.component.LocalErrorActionHandler
import dev.memoh.core.model.*
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import dev.memoh.feature.chat.PendingTurn
import dev.memoh.feature.chat.components.ErrorBanner
import dev.memoh.core.markdown.LocalMarkdownImageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatLoadingTransitionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(state: State<ChatUiState>, imageLoader: (suspend (String) -> ByteArray)? = null, onRetryRuntime: (() -> Unit)? = null) {
        compose.setContent { MemohTheme {
            CompositionLocalProvider(LocalMarkdownImageLoader provides imageLoader) {
            ChatScreen(state.value, {}, {}, {}, {}, {},
                onRetryRuntime = onRetryRuntime,
                onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                onCancelUserInput = { _, _ -> }, onDismissError = {})
            }
        } }
    }

    @Test fun snapshotTimeoutOffersRetryWhileTheTranscriptRemainsReadable() {
        val state = mutableStateOf(ChatUiState(session = Session("stalled"), historyLoading = false,
            hasMoreHistory = false, socketStatus = SocketStatus.Connected,
            history = listOf(UITurn(turnId = "saved", role = "user", text = "已经保存的消息"))))
        var retries = 0
        compose.mainClock.autoAdvance = false
        show(state, onRetryRuntime = { retries++ })
        compose.mainClock.advanceTimeBy(7_900)
        compose.onNodeWithText("重新同步").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("会话状态同步超时").assertIsDisplayed()
        compose.onNodeWithText("已经保存的消息").assertIsDisplayed()
        compose.onNodeWithText("重新同步").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, retries)
        compose.onNodeWithText("重新同步").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(awaitingSnapshot = false, runtime = RuntimeState(sessionId = "stalled")) }
        compose.mainClock.advanceTimeBy(8_300)
        compose.onNodeWithText("会话状态同步超时").assertDoesNotExist()
        compose.onNodeWithText("已经保存的消息").assertIsDisplayed()
    }

    @Test fun providerErrorDetailsAreCollapsibleAndCanBeCopied() {
        var opened: ErrorAction? = null
        val diagnostic = "HTTP 429 · insufficient_quota"
        compose.setContent { MemohTheme {
            CompositionLocalProvider(LocalErrorActionHandler provides { opened = it }) {
                ErrorBanner(UIMessage(1, "error", code = "agent.provider_quota_exhausted",
                    args = mapOf("upstream" to diagnostic), requestId = "req-123", traceId = "trace-456"))
            }
        } }
        compose.onNodeWithText("模型服务账户的余额或配额已用尽。").assertIsDisplayed()
        compose.onNodeWithText("req-123", substring = true).assertDoesNotExist()
        compose.onNodeWithText(diagnostic).assertDoesNotExist()
        compose.onNodeWithText("错误详情").performClick()
        compose.onNodeWithText("req-123", substring = true).assertIsDisplayed()
        compose.onNodeWithText("trace-456", substring = true).assertIsDisplayed()
        compose.onNodeWithText(diagnostic).assertIsDisplayed()
        screenshot("chat-error-details")
        compose.onNodeWithContentDescription("复制错误").performClick()
        compose.onNodeWithContentDescription("已复制").assertIsDisplayed()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            val report = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            assertTrue(report.contains("req-123") && report.contains("trace-456"))
            assertTrue(report.contains(diagnostic))
            assertEquals(true, clipboard.primaryClipDescription?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
        }
        compose.onNodeWithText("收起详情").performClick()
        compose.onNodeWithText("trace-456", substring = true).assertDoesNotExist()
        compose.onNodeWithText("模型服务设置").performClick()
        assertEquals(ErrorAction.Providers, opened)
    }

    @Test fun returningToLatestResumesFollowingWithoutMovingAReaderDuringUpdates() {
        val state = mutableStateOf(ChatUiState(session = Session("following"), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, socketStatus = SocketStatus.Connected,
            history = (1..30).map { UITurn(turnId = "turn-$it", role = "user", text = "消息 $it") }))
        show(state)
        compose.onNodeWithText("消息 30").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(history = state.value.history + UITurn(turnId = "turn-31", role = "user", text = "消息 31"))
        }
        compose.onNodeWithText("消息 31").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(durationMillis = 600) }
        compose.onNodeWithContentDescription("回到最新").assertIsDisplayed()

        compose.runOnIdle {
            state.value = state.value.copy(draft = "正在编辑",
                history = state.value.history + UITurn(turnId = "turn-32", role = "user", text = "消息 32"))
        }
        compose.onNodeWithText("消息 32").assertDoesNotExist()
        compose.onNodeWithContentDescription("回到最新").performClick()
        compose.onNodeWithText("消息 32").assertIsDisplayed()
        compose.onNodeWithContentDescription("回到最新").assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(history = state.value.history + UITurn(turnId = "turn-33", role = "user", text = "消息 33"))
        }
        compose.onNodeWithText("消息 33").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun unboundFolderCollapsesAfterFirstMessageAndBoundFolderStays() {
        val empty = ChatUiState(session = Session("folder-exit"), historyLoading = false, hasMoreHistory = false,
            awaitingSnapshot = false, socketStatus = SocketStatus.Connected, modelCatalogLoading = false,
            workdirs = listOf(Workdir("project", name = "项目")))
        val state = mutableStateOf(empty)
        compose.mainClock.autoAdvance = false
        show(state)
        compose.mainClock.advanceTimeBy(500)
        val editor = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val agentStart = compose.onNodeWithText("Memoh").fetchSemanticsNode().boundsInRoot.left
        compose.onNodeWithText("无文件夹").assertIsDisplayed()
        screenshot("folder-exit-before")
        compose.runOnIdle { state.value = state.value.copy(pending = listOf(PendingTurn("first", "第一条消息"))) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("无文件夹").assertExists().assertIsNotEnabled()
        val agentDuring = compose.onNodeWithText("Memoh").fetchSemanticsNode().boundsInRoot.left
        screenshot("folder-exit-during")
        compose.runOnIdle { state.value = state.value.copy(pending = emptyList(),
            history = listOf(UITurn(turnId = "first", role = "user", text = "第一条消息"))) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("无文件夹").assertDoesNotExist()
        val agentEnd = compose.onNodeWithText("Memoh").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Agent moves through intermediate positions as the folder collapses", agentDuring < agentStart && agentDuring > agentEnd)
        assertEquals(editor, compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot)
        screenshot("folder-exit-after")
        compose.runOnIdle { state.value = empty.copy(session = Session("bound-folder"), selectedWorkdirId = "project") }
        compose.mainClock.advanceTimeBy(600)
        val folder = compose.onNodeWithText("项目").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(history = listOf(UITurn(turnId = "bound-first", role = "user", text = "项目内的消息"))) }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(folder, compose.onNodeWithText("项目").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
    }

    @Test fun persistedTranscriptBecomesReadableBeforeRuntimeSnapshot() {
        val state = mutableStateOf(ChatUiState(session = Session("cached"), historyLoading = false,
            hasMoreHistory = false, socketStatus = SocketStatus.Connecting,
            history = (0 until 120).map { UITurn(turnId = "saved-$it", role = "user", text = "较早的历史消息 $it") } +
                UITurn(turnId = "saved", role = "user", text = "已经保存的会话内容")))
        show(state)
        compose.onNodeWithText("已经保存的会话内容").assertIsDisplayed()
        compose.onNodeWithText("较早的历史消息 0").assertDoesNotExist()
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        screenshot("cached-history-before-snapshot")
        val bounds = compose.onNodeWithText("已经保存的会话内容").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(socketStatus = SocketStatus.Connected, awaitingSnapshot = false,
            runtime = RuntimeState(sessionId = "cached")) }
        assertEquals(bounds, compose.onNodeWithText("已经保存的会话内容").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun slowHistoryImageKeepsItsOwnPlaceholderWithoutCoveringReadableText() {
        val imageStarted = CompletableDeferred<Unit>()
        val state = mutableStateOf(ChatUiState(session = Session("image"), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, socketStatus = SocketStatus.Connected,
            history = listOf(UITurn(turnId = "image-turn", role = "assistant", messages = listOf(
                UIMessage(0, "text", content = "已经有的文字先显示\n\n![远端图片](https://example.test/slow.png)"))))))
        show(state, imageLoader = { imageStarted.complete(Unit); awaitCancellation() })
        compose.waitUntil(3_000) { imageStarted.isCompleted }
        compose.onNodeWithText("已经有的文字先显示", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("正在加载图片").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        screenshot("history-with-slow-image")
    }

    @Test fun modelControlStaysInPlaceAndLoadedValuesCrossfadeWithoutMovingTheEditor() {
        val state = mutableStateOf(ChatUiState(session = Session("empty"), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, socketStatus = SocketStatus.Connected))
        compose.mainClock.autoAdvance = false
        show(state)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("开始对话").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        val modelBounds = compose.onNodeWithContentDescription("模型选项加载中").fetchSemanticsNode().boundsInRoot
        val editorBounds = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val agentStart = compose.onNodeWithText("Memoh").fetchSemanticsNode().boundsInRoot.left
        screenshot("composer-loading-before")
        compose.runOnIdle {
            state.value = state.value.copy(modelCatalogLoading = false,
                models = listOf(ChatModel("model", type = "chat", displayName = "Model A")), selectedModelId = "model",
                workdirs = listOf(Workdir("project", name = "演示项目文件夹")), selectedWorkdirId = "project",
                sessionInfo = SessionInfo(contextUsage = ContextUsage(usedTokens = 12_000, contextWindow = 100_000)))
        }
        compose.mainClock.advanceTimeBy(96)
        // Both texts remain composed in an intermediate frame, then the old values leave.
        compose.onNodeWithText("无文件夹").assertExists()
        compose.onNodeWithText("演示项目文件夹").assertExists()
        compose.onNodeWithText("--").assertExists()
        compose.onNodeWithText("12%").assertExists()
        val agentDuring = compose.onNodeWithText("Memoh").fetchSemanticsNode().boundsInRoot.left
        screenshot("composer-loading-during")
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("无文件夹").assertDoesNotExist()
        compose.onNodeWithText("--").assertDoesNotExist()
        compose.onNodeWithText("12%").assertIsDisplayed()
        assertEquals(modelBounds, compose.onNodeWithContentDescription("模型：Model A").fetchSemanticsNode().boundsInRoot)
        assertEquals(editorBounds, compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot)
        val agentEnd = compose.onNodeWithText("Memoh").fetchSemanticsNode().boundsInRoot.left
        assertTrue("The neighbouring Agent moves through intermediate positions", agentDuring > agentStart && agentDuring < agentEnd)
        screenshot("composer-loading-after")
    }

    @Test fun unknownHistoryHasAPlaceholderAndItsEmptyResultFadesIntoSuggestions() {
        val state = mutableStateOf(ChatUiState(session = Session("unknown"), awaitingSnapshot = false,
            socketStatus = SocketStatus.Connected))
        compose.mainClock.autoAdvance = false
        show(state)
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithContentDescription("正在加载会话").assertIsDisplayed()
        compose.onNodeWithText("开始对话").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(historyLoading = false, hasMoreHistory = false) }
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithText("开始对话").assertExists()
        compose.onNodeWithContentDescription("正在加载会话").assertExists()
        screenshot("empty-result-during")
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("开始对话").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
    }
}
