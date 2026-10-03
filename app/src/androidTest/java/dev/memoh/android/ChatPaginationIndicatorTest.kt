package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
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

@RunWith(AndroidJUnit4::class)
class ChatPaginationIndicatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun initialSkeletonAndPaginationIndicatorNeverOverlapIncludingTheFade() {
        var state by mutableStateOf(ChatUiState(session = Session("loading", title = "会话加载检查"),
            modelCatalogLoading = false, hasMoreHistory = false, awaitingSnapshot = false,
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
