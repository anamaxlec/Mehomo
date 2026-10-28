package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.SavedChatItem
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.Session
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.ConversationMatch
import dev.memoh.core.model.RuntimeCurrentRunView
import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.UITurn
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversationToolsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun searchShowsACachedTurnWithoutMixingItIntoLiveHistory() {
        val old = UITurn("old", id = "old-id", role = "user", text = "缓存里的独特目标")
        val recent = (0 until 45).map { UITurn("recent-$it", id = "id-$it", role = "user", text = "最近消息 $it") }
        var state by mutableStateOf(ChatUiState(session = Session("s"), history = recent, historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, modelCatalogLoading = false, socketStatus = SocketStatus.Connected))
        compose.setContent { MemohTheme {
            ChatScreen(state, {}, {}, {}, {}, {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {},
                onLoadSearchCache = { listOf(old) + recent })
        } }
        compose.onNodeWithContentDescription("查找会话").performClick()
        compose.onNodeWithText("查找消息或附件").performTextInput("独特目标")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("缓存里的独特目标").fetchSemanticsNodes().isNotEmpty() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "conversation-search-test.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("缓存里的独特目标").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("缓存里的独特目标").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("本地缓存").assertIsDisplayed()
        compose.onNodeWithText("缓存里的独特目标").assertIsDisplayed()
        assertEquals(recent, state.history)
    }

    @Test fun repliesCanBeSavedOpenedAndRemovedAndVoiceControlIsInvokable() {
        val reply = UITurn("t", id = "reply", role = "assistant", messages = listOf(UIMessage(0, "text", "收藏这条回复")))
        var state by mutableStateOf(ChatUiState(session = Session("s"), botId = "b", history = listOf(reply), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, modelCatalogLoading = false, socketStatus = SocketStatus.Connected))
        var voiceCalls = 0
        compose.setContent { MemohTheme {
            ChatScreen(state, {}, {}, {}, {}, {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {},
                onVoiceInput = { voiceCalls++ }, onToggleSavedTurn = {
                    state = state.copy(savedItems = listOf(SavedChatItem("b", "s", it.listKey, "收藏这条回复")))
                }, onRemoveSavedItem = { state = state.copy(savedItems = emptyList()) })
        } }
        compose.onNodeWithContentDescription("语音输入").performClick()
        assertEquals(1, voiceCalls)
        compose.onNodeWithContentDescription("更多消息操作").performClick()
        compose.onNodeWithText("收藏").performClick()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("收藏").performClick()
        compose.onNodeWithText("跳转到消息").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "conversation-saved-test.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("取消收藏").performClick()
        compose.onNodeWithText("还没有收藏的消息。").assertIsDisplayed()
    }

    @Test fun searchExpandsAReasoningStepBeforeShowingItsMatch() {
        val reply = UITurn("t", id = "reply", role = "assistant", messages = listOf(
            UIMessage(0, "reasoning", "隐藏的推理线索"), UIMessage(1, "tool", name = "read", toolCallId = "read")))
        val state = ChatUiState(session = Session("s"), history = listOf(reply), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, modelCatalogLoading = false, socketStatus = SocketStatus.Connected)
        compose.setContent { MemohTheme {
            ChatScreen(state, {}, {}, {}, {}, {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {})
        } }
        compose.onNodeWithContentDescription("查找会话").performClick()
        compose.onNodeWithText("查找消息或附件").performTextInput("推理线索")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("隐藏的推理线索").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("隐藏的推理线索").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("隐藏的推理线索").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("隐藏的推理线索").assertIsDisplayed()
    }

    @Test fun searchShowsAndNavigatesToMultipleMessagesInOneTurn() {
        val messages = (0 until 30).map { index -> UIMessage(index, "text", when (index) {
            0 -> "第一条共同目标"
            29 -> "第二条共同目标"
            else -> "其他消息 $index"
        }) }
        val state = ChatUiState(session = Session("s"), history = listOf(UITurn("t", role = "assistant", messages = messages)),
            historyLoading = false, hasMoreHistory = false, awaitingSnapshot = false,
            modelCatalogLoading = false, socketStatus = SocketStatus.Connected)
        compose.setContent { MemohTheme {
            ChatScreen(state, {}, {}, {}, {}, {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {})
        } }
        compose.onNodeWithContentDescription("查找会话").performClick()
        compose.onNodeWithText("查找消息或附件").performTextInput("共同目标")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 条结果 · 已加载内容和本地缓存").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("第二条共同目标").onFirst().assertExists()
        compose.onNodeWithText("第一条共同目标").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("查找消息或附件").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("第一条共同目标").assertIsDisplayed()
    }

    @Test fun savedLiveReplyRemainsNavigableWhenItReceivesAHistoryId() {
        val messages = (0 until 30).map { UIMessage(it, "text", if (it == 0) "收藏回复开头" else "后续内容 $it") }
        val saved = SavedChatItem("b", "s", "assistant:turn", "收藏回复开头", provisionalTurnId = "turn")
        var state by mutableStateOf(ChatUiState(session = Session("s"), botId = "b", savedItems = listOf(saved),
            runtime = RuntimeState(sessionId = "s", run = RuntimeCurrentRunView(runId = "run", turnId = "turn", status = "completed", messages = messages)),
            historyLoading = false, hasMoreHistory = false, awaitingSnapshot = false,
            modelCatalogLoading = false, socketStatus = SocketStatus.Connected))
        compose.setContent { MemohTheme {
            ChatScreen(state, {}, {}, {}, {}, {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {})
        } }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("收藏").performClick()
        compose.onNodeWithText("跳转到消息").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("跳转到消息").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("收藏回复开头").assertIsDisplayed()

        compose.onNodeWithContentDescription("查找会话").performClick()
        compose.onNodeWithText("查找消息或附件").performTextInput("后续内容 27")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 条结果 · 已加载内容和本地缓存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("后续内容 27") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("查找消息或附件").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("后续内容 27").assertIsDisplayed()

        compose.runOnIdle {
            state = state.copy(history = listOf(UITurn("turn", id = "server-id", role = "assistant", messages = messages)))
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        compose.onNodeWithContentDescription("更多消息操作").performClick()
        compose.onNodeWithText("取消收藏").assertIsDisplayed()
    }

    @Test fun anOfflineSearchResultOpensItsCachedMessage() {
        val cached = UITurn("old", id = "old-id", role = "user", text = "离线搜索目标")
        val latest = UITurn("new", id = "new-id", role = "user", text = "最新消息")
        val state = ChatUiState(session = Session("s"), history = listOf(latest), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, modelCatalogLoading = false, socketStatus = SocketStatus.Connected)
        var consumed = false
        compose.setContent { MemohTheme {
            ChatScreen(state, {}, {}, {}, {}, {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {},
                externalSearchHit = ConversationMatch(cached.listKey, null, "我", cached.text.orEmpty()),
                externalCachedTurn = cached, onExternalSearchConsumed = { consumed = true })
        } }
        compose.waitUntil(5_000) { consumed }
        compose.onNodeWithText("本地缓存").assertIsDisplayed()
        compose.onNodeWithText("离线搜索目标").assertIsDisplayed()
    }

    @Test fun changedLiveMessageDoesNotReplaceAnOfflineSearchHit() {
        val cached = UITurn("t", id = "id", role = "assistant", messages = listOf(UIMessage(0, "text", "缓存中的旧目标")))
        val current = cached.copy(messages = listOf(UIMessage(0, "text", "服务端的新内容")))
        val state = ChatUiState(session = Session("s"), history = listOf(current), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, modelCatalogLoading = false, socketStatus = SocketStatus.Connected)
        var consumed = false
        compose.setContent { MemohTheme {
            ChatScreen(state, {}, {}, {}, {}, {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {},
                externalSearchHit = ConversationMatch(cached.listKey, 0, "助手", "缓存中的旧目标"),
                externalCachedTurn = cached, onExternalSearchConsumed = { consumed = true })
        } }
        compose.waitUntil(5_000) { consumed }
        compose.onNodeWithText("本地缓存").assertIsDisplayed()
        compose.onNodeWithText("缓存中的旧目标").assertIsDisplayed()
    }
}
