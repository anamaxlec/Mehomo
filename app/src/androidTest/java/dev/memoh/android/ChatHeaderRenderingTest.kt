package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ChatHeaderRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val title = "这是一个很长的中文会话标题，用来检查工具栏的文字省略和垂直居中"
    private val ready = ChatUiState(session = Session("header", title = title), historyLoading = false,
        hasMoreHistory = false, modelCatalogLoading = false, awaitingSnapshot = false,
        socketStatus = SocketStatus.Connected, runtime = RuntimeState(sessionId = "header"),
        history = listOf(UITurn("saved", role = "assistant", messages = listOf(UIMessage(0, "text", "已有回复保持原位")))))
    private var state by mutableStateOf(ready)
    private var fontScale by mutableFloatStateOf(1f)
    private var rtl by mutableStateOf(false)
    private var dark by mutableStateOf(false)
    private var controlsOpened = 0

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MemohTheme(darkTheme = dark) {
                    ChatScreen(state, {}, {}, {}, {}, {}, onOpenControls = { controlsOpened++ },
                        onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                        onCancelUserInput = { _, _ -> }, onDismissError = {})
                }
            }
        }
        advance(600)
    }

    private fun advance(ms: Long = 500) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun more() = compose.onNodeWithContentDescription("更多")
    private fun quiet() = more().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    private fun attention(text: String) = more().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text))
    private fun bounds(): Map<String, Rect> = mapOf(
        "title" to compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot,
        "back" to compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot,
        "more" to more().fetchSemanticsNode().boundsInRoot,
        "workspace" to compose.onNodeWithContentDescription("打开工作区").fetchSemanticsNode().boundsInRoot,
        "reply" to compose.onNodeWithText("已有回复保持原位").fetchSemanticsNode().boundsInRoot,
        "editor" to compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot,
    )

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Popup placement becomes known in a native layout pass. Advance its
        // entrance after that pass before capturing all windows on screen.
        advance()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(100)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun titleIsVerticallyCenteredWithLongTextLargeFontsAndMirroredLayout() {
        show()
        for ((scale, mirrored, night) in listOf(Triple(1f, false, false), Triple(1.6f, false, false), Triple(1.6f, true, true))) {
            compose.runOnIdle { fontScale = scale; rtl = mirrored; dark = night }
            advance()
            val positions = bounds()
            val label = positions.getValue("title")
            for (action in listOf("back", "more", "workspace")) {
                assertTrue("Title and $action share a vertical center at fontScale=$scale RTL=$mirrored: $positions",
                    abs(label.center.y - positions.getValue(action).center.y) <= 1f)
            }
            val more = positions.getValue("more")
            assertTrue("A long title never overlaps the actions", if (mirrored) label.left >= more.right else label.right <= more.left)
            quiet()
            screenshot("chat-header-centered-${scale}-${if (mirrored) "rtl-dark" else "ltr-light"}")
        }
    }

    @Test fun connectionBadgeAndMenuLeaveTheTitleActionsAndTranscriptInPlace() {
        show()
        val original = bounds()
        quiet()
        compose.runOnIdle { state = state.copy(socketStatus = SocketStatus.Reconnecting, awaitingSnapshot = true) }
        advance(600)
        quiet()
        assertEquals(original, bounds())
        compose.runOnIdle { state = ready }
        advance(100)
        quiet()
        compose.runOnIdle { state = state.copy(socketStatus = SocketStatus.Reconnecting, awaitingSnapshot = true) }
        advance(1_600)
        attention("正在恢复连接…")
        compose.onNodeWithText("正在恢复连接…").assertDoesNotExist()
        assertEquals(original, bounds())
        screenshot("chat-header-reconnecting-badge")
        more().performClick(); advance()
        compose.onNodeWithText("连接状态").assertIsDisplayed()
        compose.onNodeWithText("正在恢复连接…").assertIsDisplayed()
        compose.onNodeWithText("记忆").assertIsDisplayed()
        compose.onNodeWithText("日程").assertIsDisplayed()
        screenshot("chat-header-connection-menu")
        compose.runOnIdle { state = ready }
        advance(64)
        quiet()
        compose.onNodeWithText("已连接").assertIsDisplayed()
        compose.onNodeWithText("正在恢复连接…").assertDoesNotExist()
        compose.onNodeWithText("会话控制").performClick(); advance()
        assertEquals(1, controlsOpened)
        assertEquals(original, bounds())
        compose.runOnIdle { state = ready.copy(socketStatus = SocketStatus.Closed) }
        advance()
        attention("连接已断开")
        assertEquals(original, bounds())
        more().performClick(); advance()
        compose.onNodeWithText("连接已断开").assertIsDisplayed()
        screenshot("chat-header-disconnected-menu")
        compose.onNodeWithText("会话控制").performClick(); advance()
        compose.runOnIdle { state = ready.copy(runtime = RuntimeState(sessionId = "header",
            run = RuntimeCurrentRunView(runId = "run", turnId = "active"))) }
        advance()
        quiet()
        assertEquals(original.getValue("title"), bounds().getValue("title"))
        assertEquals(original.getValue("more"), bounds().getValue("more"))
    }

    @Test fun enteringAnotherSessionResetsAnOldWarningAndExposesOnlyItsOwnState() {
        state = ready.copy(socketStatus = SocketStatus.Reconnecting, awaitingSnapshot = true)
        show(); advance(1_600)
        attention("正在恢复连接…")
        compose.runOnIdle { state = ready.copy(session = Session("new", title = title),
            socketStatus = SocketStatus.Connecting, awaitingSnapshot = true, runtime = RuntimeState.EMPTY) }
        advance(600)
        quiet()
        advance(1_100)
        attention("正在连接会话…")
        compose.runOnIdle { state = state.copy(socketStatus = SocketStatus.Connected) }
        advance()
        attention("正在连接会话…")
        compose.runOnIdle { state = state.copy(runtime = RuntimeState(sessionId = "new")) }
        advance()
        attention("正在同步会话…")
        compose.runOnIdle { state = state.copy(awaitingSnapshot = false) }
        advance()
        quiet()
        compose.onNodeWithText("已连接").assertDoesNotExist()
        more().performClick(); advance()
        compose.onNodeWithText("已连接").assertIsDisplayed()
    }
}
