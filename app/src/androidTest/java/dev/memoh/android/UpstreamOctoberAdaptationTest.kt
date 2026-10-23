package dev.memoh.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.designsystem.component.MemohPopupMenu
import dev.memoh.core.markdown.*
import dev.memoh.core.model.*
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class UpstreamOctoberAdaptationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun anInitiallyClosedPopupStillOpensClosesAndReopens() {
        val expanded = mutableStateOf(false)
        var actions = 0
        compose.setContent { MemohTheme {
            Box {
                androidx.compose.material3.TextButton(onClick = { expanded.value = true }) {
                    androidx.compose.material3.Text("打开测试菜单")
                }
                MemohPopupMenu(expanded.value, { expanded.value = false }, Alignment.BottomEnd) {
                    androidx.compose.material3.TextButton(onClick = { actions++; expanded.value = false }) {
                        androidx.compose.material3.Text("执行测试操作")
                    }
                }
            }
        } }
        compose.onNodeWithText("执行测试操作").assertDoesNotExist()
        repeat(2) {
            compose.onNodeWithText("打开测试菜单").performClick()
            compose.onNodeWithText("执行测试操作").assertIsDisplayed().performClick()
            compose.onNodeWithText("执行测试操作").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(2, actions) }
    }

    @Test fun slashSkillsFillTheDraftAndRunningActionsStayConsistent() {
        val state = mutableStateOf(ChatUiState(session = Session("skills", runtimeType = "claude-code"),
            historyLoading = false, hasMoreHistory = false, sessionLoading = false, awaitingSnapshot = false,
            socketStatus = SocketStatus.Connected, modelCatalogLoading = false, draft = "/",
            controls = RuntimeControls(commands = listOf(
                RuntimeCommand(name = "status", kind = "read", description = "查看会话状态"),
                RuntimeCommand(name = "dataviz", kind = "turn", description = "将数据绘制成图表", skill = true)))))
        var stopped = 0
        val sent = mutableListOf<String>()
        compose.setContent { MemohTheme(darkTheme = false) {
            ChatScreen(state.value, {}, { sent += it }, { stopped++ },
                { state.value = state.value.copy(draft = it) }, {},
                onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {})
        } }
        compose.onNodeWithText("命令").assertIsDisplayed()
        compose.onNodeWithText("Skills").assertIsDisplayed()
        compose.onNodeWithText("/dataviz").assertIsDisplayed()
        screenshot("upstream-skills-menu")
        compose.onNodeWithText("/dataviz").performClick()
        compose.runOnIdle { assertEquals("/dataviz ", state.value.draft); assertTrue(sent.isEmpty()) }
        compose.onNode(hasSetTextAction()).performTextInput("绘图")
        compose.runOnIdle { assertEquals("/dataviz 绘图", state.value.draft) }
        compose.onNodeWithText("Skills").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(draft = "/group", controls = RuntimeControls(commands = listOf(
                RuntimeCommand(name = "group-true", kind = "turn", skill = true))))
        }
        compose.onNodeWithText("/group-true").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("/group-true ", state.value.draft) }
        compose.runOnIdle {
            state.value = state.value.copy(draft = "补充资料", runtime = RuntimeState(run =
                RuntimeCurrentRunView(runId = "run", turnId = "turn")))
        }
        compose.onNodeWithContentDescription("加入后续队列").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("补充资料"), sent); assertEquals(0, stopped) }
        compose.runOnIdle { state.value = state.value.copy(draft = "", busy = true) }
        compose.onNodeWithContentDescription("停止生成").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stopped); state.value = state.value.copy(busy = false, attachmentLoading = true) }
        compose.onNodeWithContentDescription("停止生成").assertDoesNotExist()
        compose.onNodeWithContentDescription("加入后续队列").assertIsNotEnabled()
    }

    @Test fun localModelAndNewShortcutsWorkFromTheMenuAndTypedSelectors() {
        val state = mutableStateOf(ChatUiState(session = Session("native", runtimeType = "model"),
            historyLoading = false, hasMoreHistory = false, sessionLoading = false, awaitingSnapshot = false,
            socketStatus = SocketStatus.Closed, modelCatalogLoading = false, draft = "/",
            models = listOf(ChatModel(id = "m", name = "测试模型", type = "chat")),
            skillCatalog = listOf(SkillCatalogItem("dataviz", description = "将数据绘制成图表"))))
        var created = 0
        val sent = mutableListOf<String>()
        compose.setContent { MemohTheme(darkTheme = false) {
            ChatScreen(state.value, {}, { sent += it }, {}, { state.value = state.value.copy(draft = it) }, {},
                onApprove = { _, _, _ -> }, onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> },
                onCancelUserInput = { _, _ -> }, onDismissError = {}, onNewSession = { created++ })
        } }
        compose.onNodeWithText("快捷操作").assertIsDisplayed()
        compose.onNodeWithText("/dataviz").assertIsDisplayed()
        screenshot("slash-shortcuts")
        compose.onNodeWithText("切换模型").performClick()
        compose.onNodeWithText("其他供应商 · 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals("", state.value.draft); assertTrue(sent.isEmpty()) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasSetTextAction()).performTextReplacement("/model")
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        compose.onNodeWithText("其他供应商 · 1").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasSetTextAction()).performTextReplacement("/new")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { assertEquals(1, created); assertTrue(sent.isEmpty()) }
        compose.onNode(hasSetTextAction()).performTextReplacement("/")
        compose.onNodeWithText("新建会话").performClick()
        compose.runOnIdle { assertEquals(2, created); assertTrue(sent.isEmpty()) }
    }

    @Test fun publishedImagesLoadButWorkspaceImagesAndUnavailableMediaNeverHitTheApi() {
        val requested = CopyOnWriteArrayList<String>()
        val opened = mutableListOf<WorkspaceLink>()
        val externalLinks = mutableListOf<String>()
        val archived = "/bots/b/media/${"a".repeat(64)}"
        val bitmap = Bitmap.createBitmap(320, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff8374c5.toInt()) }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        compose.setContent { MemohTheme(darkTheme = true) {
            CompositionLocalProvider(LocalMarkdownImageLoader provides { path: String -> requested += path; bytes },
                LocalWorkspaceLinkHandler provides { opened += it }) {
                Surface { Column(Modifier.padding(20.dp)) {
                    MemohMarkdown.Markdown("![工作区图片](/data/chart.png)\n\n" +
                        "![缺失图片](memoh-media-error:media.reference_unavailable)\n\n" +
                        "[中文 HTML 产物](/data/结果.html)\n\n![归档图片]($archived)\n\n" +
                        "[![链接图片]($archived)](https://example.test/report)",
                        onLinkClick = { externalLinks += it }, singleDollarMath = false, workspaceImagesAsLinks = true)
                } }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("链接图片").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("引用的图片暂不可用", substring = true).assertIsDisplayed()
        compose.onNodeWithText("工作区图片").performClick()
        compose.runOnIdle { assertEquals(listOf(WorkspaceLink.File("/data/chart.png")), opened) }
        compose.onNodeWithText("中文 HTML 产物", substring = true).performClick()
        compose.runOnIdle {
            assertEquals(WorkspaceLink.File("/data/结果.html"), opened.last())
            assertEquals(listOf(archived, archived), requested.toList())
        }
        compose.onNodeWithContentDescription("链接图片").performClick()
        compose.runOnIdle { assertEquals(listOf("https://example.test/report"), externalLinks) }
        screenshot("upstream-markdown-media")
    }
}
