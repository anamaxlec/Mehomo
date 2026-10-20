package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.Session
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UITurn
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ChatPaginationIndicatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun knownEmptyConversationShowsSuggestionsWhileFirstSnapshotConnects() {
        var state by mutableStateOf(ChatUiState(session = Session("initial-snapshot"), historyLoading = false,
            hasMoreHistory = false, modelCatalogLoading = false, socketStatus = SocketStatus.Connecting))
        compose.setContent { MemohTheme { ChatScreen(state, {}, {}, {}, {}, {},
            onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
            onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        compose.onNodeWithText("重连中…").assertDoesNotExist()
        compose.onNodeWithContentDescription("正在连接会话").assertDoesNotExist()
        compose.onNodeWithText("开始对话").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(awaitingSnapshot = false, socketStatus = SocketStatus.Connected,
                runtime = dev.memoh.core.model.RuntimeState(sessionId = "initial-snapshot"))
        }
        compose.onNodeWithText("已连接").assertDoesNotExist()
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
    }

    @Test fun shortRepliesOnlyLoadOlderHistoryAfterTheReaderPullsDown() {
        val calls = AtomicInteger()
        var state by mutableStateOf(ChatUiState(session = Session("short-reply"), historyLoading = false,
            hasMoreHistory = true, awaitingSnapshot = false, socketStatus = SocketStatus.Connected,
            history = listOf(UITurn("first", role = "user", text = "第一条消息"),
                UITurn("first", role = "assistant", messages = listOf(UIMessage(0, "text", "第一条回复"))))))
        compose.setContent { MemohTheme { ChatScreen(state, {}, {}, {}, {}, {
            calls.incrementAndGet()
            state = state.copy(loadingMore = true)
        }, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
            onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        compose.onNodeWithText("第一条回复").assertIsDisplayed()
        assertEquals(0, calls.get())
        compose.onNodeWithContentDescription("正在加载更早消息").assertDoesNotExist()
        screenshot("short-reply-without-pagination")
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(durationMillis = 600) }
        compose.waitUntil(3_000) { calls.get() > 0 }
        assertEquals(1, calls.get())
        compose.onNodeWithContentDescription("正在加载更早消息").assertIsDisplayed()
        compose.onNodeWithText("第一条回复").assertIsDisplayed()
    }

    @Test fun healthyConnectionsAndQuickReconnectsNeverChangeTheHeader() {
        var state by mutableStateOf(ChatUiState(session = Session("quiet-connection"), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, socketStatus = SocketStatus.Connected))
        compose.setContent { MemohTheme { ChatScreen(state, {}, {}, {}, {}, {},
            onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
            onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        val title = compose.onNodeWithText("会话").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("已连接").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(socketStatus = SocketStatus.Reconnecting, awaitingSnapshot = true,
            runtime = dev.memoh.core.model.RuntimeState(sessionId = "quiet-connection")) }
        compose.onNodeWithText("正在恢复连接…").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(socketStatus = SocketStatus.Connected, awaitingSnapshot = false) }
        compose.onNodeWithText("已连接").assertDoesNotExist()
        compose.onNodeWithText("正在恢复连接…").assertDoesNotExist()
        assertEquals(title, compose.onNodeWithText("会话").fetchSemanticsNode().boundsInRoot)
        screenshot("quiet-connection-header")
    }

    @Test fun persistentConnectionLossShowsFeedbackWithoutMovingContentOrDisablingDetails() {
        var state by mutableStateOf(ChatUiState(session = Session("persistent-disconnect"), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, socketStatus = SocketStatus.Connected,
            runtime = dev.memoh.core.model.RuntimeState(sessionId = "persistent-disconnect"),
            history = listOf(UITurn("saved", role = "assistant", messages = listOf(
                UIMessage(0, "tool", name = "exec", toolCallId = "saved-call",
                    input = kotlinx.serialization.json.Json.parseToJsonElement("""{"description":"查看已有输出"}"""),
                    output = kotlinx.serialization.json.JsonPrimitive("已有工具详情")))))))
        compose.setContent { MemohTheme { ChatScreen(state, {}, {}, {}, {}, {},
            onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
            onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        compose.onNodeWithText("查看已有输出").performClick()
        val original = compose.onNodeWithText("已有工具详情").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state = state.copy(socketStatus = SocketStatus.Reconnecting, awaitingSnapshot = true) }
        val reconnecting = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在恢复连接…")
        compose.waitUntil(4_000) { compose.onAllNodes(reconnecting).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").assert(reconnecting)
        compose.onNodeWithText("正在恢复连接…").assertDoesNotExist()
        assertEquals(original, compose.onNodeWithText("已有工具详情").fetchSemanticsNode().boundsInRoot)
        screenshot("persistent-connection-feedback")
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("连接状态").assertIsDisplayed()
        compose.onNodeWithText("正在恢复连接…").assertIsDisplayed()
        compose.onNodeWithText("会话控制").performClick()
        assertEquals(original, compose.onNodeWithText("已有工具详情").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("查看已有输出").performClick()
        compose.onNodeWithText("已有工具详情").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(socketStatus = SocketStatus.Connected, awaitingSnapshot = false) }
        compose.onNodeWithText("正在恢复连接…").assertDoesNotExist()
        compose.onNodeWithText("已连接").assertDoesNotExist()
    }

    @Test fun initialSkeletonAndPaginationIndicatorNeverOverlapIncludingTheFade() {
        var state by mutableStateOf(ChatUiState(session = Session("loading", title = "会话加载检查"),
            modelCatalogLoading = false, hasMoreHistory = true, awaitingSnapshot = false,
            socketStatus = SocketStatus.Connected))
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme { ChatScreen(state = state, onBack = {}, onSend = {}, onStop = {},
            onDraftChange = {}, onLoadOlder = {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
            onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithContentDescription("正在加载会话").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(historyLoading = false, loadingMore = true,
                history = listOf(UITurn(turnId = "one", role = "user", text = "你好")))
        }
        var sawFade = false
        var sawPagination = false
        repeat(50) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val skeleton = compose.onAllNodesWithContentDescription("正在加载会话").fetchSemanticsNodes().isNotEmpty()
            val indicator = compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().isNotEmpty()
            org.junit.Assert.assertFalse("Skeleton and pagination must not be visible in the same frame", skeleton && indicator)
            if (skeleton) sawFade = true
            if (indicator) sawPagination = true
        }
        org.junit.Assert.assertTrue("The skeleton keeps its exit animation", sawFade)
        org.junit.Assert.assertTrue("Pagination appears after the skeleton leaves", sawPagination)
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }

    @Test fun paginationUsesAFullSizeIndicatorWithoutMovingTheConversation() {
        var state by mutableStateOf(ChatUiState(session = Session("pagination", title = "刷新指示器检查"),
            historyLoading = false, awaitingSnapshot = false, hasMoreHistory = false, socketStatus = SocketStatus.Connected,
            history = listOf(UITurn(turnId = "one", role = "user", text = "你好"),
                UITurn(turnId = "one", role = "assistant", messages = listOf(UIMessage(0, "text", content = "会话内容保持在原来的位置。"))))))
        compose.setContent { MemohTheme { ChatScreen(state = state, onBack = {}, onSend = {}, onStop = {},
            onDraftChange = {}, onLoadOlder = {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
            onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {}) } }
        compose.onNodeWithText("你好").assertIsDisplayed()
        val original = compose.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state = state.copy(loadingMore = true) }
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        assertEquals(original, compose.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "chat-pagination-indicator.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        compose.runOnIdle { state = state.copy(loadingMore = false) }
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        assertEquals(original, compose.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot)
    }
}
