package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MessageActionsRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ready = ChatUiState(session = Session("reply", runtimeType = "model"),
        historyLoading = false, hasMoreHistory = false, awaitingSnapshot = false,
        socketStatus = SocketStatus.Connected, modelCatalogLoading = false,
        history = listOf(UITurn("turn", role = "assistant",
            messages = listOf(UIMessage(0, "text", "已经完成的回复")))))

    private fun show(state: State<ChatUiState>, onRetry: (String) -> Unit = {}) {
        compose.setContent { MemohTheme(darkTheme = false) {
            ChatScreen(state.value, {}, {}, {}, {}, {}, onRegenerate = onRetry,
                onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                onCancelUserInput = { _, _ -> }, onDismissError = {})
        } }
    }

    private fun screenshot(name: String, iconOnly: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val node = if (iconOnly) compose.onNodeWithContentDescription("重新生成") else compose.onRoot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            node.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun draftAndAttachmentLoadingDoesNotDelayRetryOrTheOtherActions() {
        val state = mutableStateOf(ready.copy(draftRestoring = true, attachmentLoading = true))
        show(state)
        compose.onNodeWithContentDescription("复制消息").assertIsEnabled()
        compose.onNodeWithContentDescription("更多消息操作").assertIsEnabled()
        compose.onNodeWithContentDescription("重新生成").assertIsEnabled()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        val bounds = compose.onNodeWithContentDescription("重新生成").fetchSemanticsNode().boundsInRoot
        screenshot("retry-ready-during-composer-loading")
        compose.runOnIdle { state.value = ready }
        compose.onNodeWithContentDescription("重新生成").assertIsEnabled()
        assertEquals(bounds, compose.onNodeWithContentDescription("重新生成").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun onlyTheNewestPersistedReplyCanRetryWhenRecordsShareATurnId() {
        val original = ready.history.single()
        val state = mutableStateOf(ready.copy(history = listOf(original.copy(id = "older"), original.copy(id = "latest"))))
        show(state)
        val buttons = compose.onAllNodesWithContentDescription("重新生成")
        buttons.assertCountEquals(2)
        buttons.onFirst().assertIsNotEnabled()
        buttons.onLast().assertIsEnabled()
    }

    @Test fun actualReconnectDisablesClicksImmediatelyAndRestoresTheIconWithATransition() {
        val state = mutableStateOf(ready.copy(socketStatus = SocketStatus.Reconnecting, awaitingSnapshot = true))
        var retries = 0
        compose.mainClock.autoAdvance = false
        show(state) { retries++ }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("重新生成").assertIsNotEnabled()
        val bounds = compose.onNodeWithContentDescription("重新生成").fetchSemanticsNode().boundsInRoot
        screenshot("retry-disabled", iconOnly = true)
        compose.runOnIdle { state.value = ready }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithContentDescription("重新生成").assertIsEnabled()
        screenshot("retry-enabling", iconOnly = true)
        compose.mainClock.advanceTimeBy(600)
        screenshot("retry-enabled", iconOnly = true)
        assertEquals(bounds, compose.onNodeWithContentDescription("重新生成").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("重新生成").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state.value = ready.copy(socketStatus = SocketStatus.Reconnecting, awaitingSnapshot = true) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("重新生成").assertIsNotEnabled().performTouchInput { click() }
        assertEquals("A fading icon must already reject clicks after disconnect", 1, retries)
    }
}
