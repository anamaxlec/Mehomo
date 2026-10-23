package dev.memoh.android

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.ChatModel
import dev.memoh.core.model.Session
import dev.memoh.core.model.SkillCatalogItem
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
class ComposerMenuLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val longSkill = "build-a-very-long-android-project-and-verify-release"
    private fun state(draft: String = "/") = ChatUiState(
        session = Session("menu-layout", runtimeType = "model"),
        historyLoading = false, hasMoreHistory = false, sessionLoading = false, awaitingSnapshot = false,
        socketStatus = SocketStatus.Connected, modelCatalogLoading = false, draft = draft,
        models = listOf(ChatModel(id = "m", name = "GPT 6.1 Sol", type = "chat")), selectedModelId = "m",
        skillCatalog = listOf(
            SkillCatalogItem("browser-testing", description =
                "Write and run project-local browser regression tests using Playwright Test."),
            SkillCatalogItem("dataviz", description = "把上传的数据整理成清晰的图表，保留原始数据。"),
            SkillCatalogItem(longSkill, description = "构建并检查 Android 项目、签名与 Release 产物。")))

    private fun screenshot(name: String, systemUi: Boolean = false, tag: String? = null) {
        val bitmap = if (systemUi) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else (tag?.let { compose.onNodeWithTag(it) } ?: compose.onRoot()).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun aNarrowLargeTextMenuKeepsActionsReachableAndPreservesTheCompletedSelector() {
        val state = mutableStateOf(state())
        val largeText = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText.value) 1.5f else 1f)) {
                MemohTheme(darkTheme = largeText.value) {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("narrow-menu")) {
                        ChatScreen(state.value, {}, {}, {}, { state.value = state.value.copy(draft = it) }, {},
                            onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                            onCancelUserInput = { _, _ -> }, onDismissError = {})
                    }
                }
            }
        }
        compose.onNodeWithText("帮助").assertIsDisplayed()
        compose.onNodeWithText("切换模型").assertIsDisplayed()
        compose.onNodeWithText("/browser-testing").assertIsDisplayed()
        screenshot("slash-refined-narrow-light", tag = "narrow-menu")
        compose.runOnIdle { largeText.value = true }
        compose.onNodeWithText("帮助").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("切换模型").performScrollTo().assertIsDisplayed()
        screenshot("slash-refined-large-dark", tag = "narrow-menu")
        compose.onNode(hasScrollAction() and SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "命令与技能"))
            .performScrollToNode(hasText("/$longSkill"))
        compose.onNodeWithText("/$longSkill").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("构建项目")
        compose.runOnIdle { assertEquals("/$longSkill 构建项目", state.value.draft) }
    }

    @Test fun aVisibleKeyboardKeepsTheMenuAndEditorControlsUsable() {
        val state = mutableStateOf(state(draft = ""))
        val keyboardVisible = AtomicBoolean(false)
        val keyboardBottom = AtomicInteger()
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent { MemohTheme(darkTheme = false) {
            val imeVisible = WindowInsets.isImeVisible
            val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { keyboardVisible.set(imeVisible); keyboardBottom.set(imeBottom) }
            ChatScreen(state.value, {}, {}, {}, { state.value = state.value.copy(draft = it) }, {},
                onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                onCancelUserInput = { _, _ -> }, onDismissError = {})
        } }
        compose.onNode(hasSetTextAction()).performTouchInput { click() }
        compose.waitUntil(8_000) { keyboardVisible.get() && keyboardBottom.get() > 0 }
        compose.onNode(hasSetTextAction()).performTextInput("/")
        compose.onNodeWithText("帮助").assertIsDisplayed()
        compose.onNodeWithContentDescription("添加文件或应用").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        screenshot("slash-refined-keyboard", systemUi = true)
        compose.onNodeWithText("/browser-testing").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("测试页面")
        compose.runOnIdle {
            assertTrue(keyboardVisible.get())
            assertEquals("/browser-testing 测试页面", state.value.draft)
        }
        compose.onNodeWithText("会话").assertIsDisplayed()
        screenshot("slash-refined-keyboard-writing", systemUi = true)
    }

    @Test fun anInterruptedExitPreservesRowsAndKeepsTheEditorUsable() {
        val state = mutableStateOf(state(draft = ""))
        compose.setContent { MemohTheme(darkTheme = false) {
            ChatScreen(state.value, {}, {}, {}, { state.value = state.value.copy(draft = it) }, {},
                onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                onCancelUserInput = { _, _ -> }, onDismissError = {})
        } }
        val editor = compose.onNode(hasSetTextAction())
        editor.performClick()
        compose.mainClock.autoAdvance = false
        try {
            editor.performTextInput("/")
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            screenshot("slash-motion-enter")
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithText("帮助").assertIsDisplayed()
            screenshot("slash-motion-open")

            editor.performTextClearance()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithText("/browser-testing").assertExists().assertIsNotEnabled()
            compose.onNodeWithText("暂无可用的 Skill").assertDoesNotExist()
            screenshot("slash-motion-exit")

            // Typing again before the exit ends must reverse the same menu,
            // without replacing the editor or leaving it unable to select.
            editor.performTextInput("/")
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithText("/browser-testing").performScrollTo().assertIsEnabled().performClick()
            // With the animation clock paused, apply the completion to the
            // editor before delivering the next IME edit.
            compose.mainClock.advanceTimeByFrame()
            editor.assertTextContains("/browser-testing ")
            editor.assertIsFocused().performTextInput("测试页面")
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithText("帮助").assertDoesNotExist()
            compose.onNodeWithContentDescription("发送").assertIsDisplayed()
            compose.runOnIdle { assertEquals("/browser-testing 测试页面", state.value.draft) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
