package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.component.LocalMemohPopupFocusable
import dev.memoh.core.designsystem.component.LocalMemohPopupBounds
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.feature.chat.ChatUiState
import dev.memoh.feature.chat.components.ContextInspectorSheet
import dev.memoh.feature.chat.components.SessionContextIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContextPanelRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val usage = ContextUsage(usedTokens = 22000, contextWindow = 256000,
        breakdown = listOf(ContextKindUsage("system_prompt", tokenEstimate = 4096),
            ContextKindUsage("current_user_message", tokenEstimate = 304)),
        toolDefs = listOf(ContextToolUsage("mcp", tokenEstimate = 18000)))
    private val state = ChatUiState(session = Session("context-panel"), sessionInfo = SessionInfo(contextUsage = usage))

    @Test fun officialSummaryShowsThreeGroupsAndHandsOffToTheInspector() {
        var refreshes = 0
        var inspecting by mutableStateOf(false)
        compose.setContent { MemohTheme(darkTheme = false) { Surface {
            CompositionLocalProvider(LocalMemohPopupFocusable provides false) {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
                    SessionContextIndicator(state, { refreshes++ }, { inspecting = true })
                }
                if (inspecting) ContextInspectorSheet(state, {}, { inspecting = false })
            }
        } } }
        compose.onNodeWithContentDescription("会话信息，上下文已用 9%").performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithText("~22.4K / 256.0K").assertIsDisplayed()
        listOf("设定", "工具", "对话", "4.1K", "18.0K", "304").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("上下文方格图，已用 9%，22.4K tokens").assertIsDisplayed()
        screenshot("context-panel-official-light")
        compose.onNodeWithContentDescription("上下文检查器").performClick()
        compose.onNodeWithText("上下文检查器").assertIsDisplayed()
        compose.onNodeWithText("~22.4K / 256.0K").assertDoesNotExist()
    }

    @Test fun darkLargeTextKeepsTheLegendAndAutomaticCompactionNoticeVisible() {
        val fullUsage = usage.copy(breakdown = listOf(ContextKindUsage("system_prompt", tokenEstimate = 180000),
            ContextKindUsage("current_user_message", tokenEstimate = 5000)), toolDefs = listOf(ContextToolUsage("mcp", tokenEstimate = 1000)),
            budgetPlan = ContextBudgetPlan(256000, 8000), compaction = ContextCompaction(true, 200000))
        compose.setContent { MemohTheme(darkTheme = true) { Surface {
            val density = LocalDensity.current
            val bounds = with(density) { IntRect(20.dp.roundToPx(), 0, 280.dp.roundToPx(), 0) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f), LocalMemohPopupBounds provides bounds) {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomStart) {
                    Box(Modifier.width(260.dp), contentAlignment = Alignment.BottomEnd) {
                        SessionContextIndicator(state.copy(sessionInfo = SessionInfo(contextUsage = fullUsage)), {})
                    }
                }
            }
        } } }
        compose.onNodeWithContentDescription("会话信息，上下文已用 73%").performClick()
        compose.onNodeWithText("~186.0K / 256.0K").assertIsDisplayed()
        compose.onNodeWithText("设定").assertIsDisplayed()
        compose.onNodeWithText("工具").assertIsDisplayed()
        compose.onNodeWithText("对话").assertIsDisplayed()
        compose.onNodeWithText("快满了，较早的对话会被自动整理").assertIsDisplayed()
        screenshot("context-panel-official-dark-large")
    }

    @Test fun unknownWindowDoesNotInventAPercentageOrGridAndCanRetry() {
        var refreshes = 0
        val unavailable = state.copy(sessionInfo = SessionInfo(contextUsage = ContextUsage(usedTokens = 22400)),
            sessionInfoError = "上下文信息加载失败")
        compose.setContent { MemohTheme { Surface {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
                SessionContextIndicator(unavailable, { refreshes++ })
            }
        } } }
        compose.onNodeWithContentDescription("会话信息，点击查看上下文").performClick()
        compose.onNodeWithText("22.4K / --").assertIsDisplayed()
        compose.onNodeWithContentDescription("上下文方格图", substring = true).assertDoesNotExist()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithText("重试").performClick()
        assertEquals(2, refreshes)
    }

    @Test fun inspectorPreservesManualCompactionAndSessionDetails() {
        var compactions = 0
        var sessionRefreshes = 0
        var lifecycleRefreshes = 0
        var dismissed = false
        val current = state.copy(sessionInfo = SessionInfo(messageCount = 7,
            contextUsage = usage.copy(compaction = ContextCompaction(false)),
            cacheStats = SessionCacheStats(cacheReadTokens = 8000, cacheHitRate = 40.0)))
        compose.setContent { MemohTheme { Surface {
            ContextInspectorSheet(current, { lifecycleRefreshes++ }, { dismissed = true }, { sessionRefreshes++ }, { compactions++ })
        } } }
        compose.onNodeWithText("刷新").performClick()
        assertEquals(1, lifecycleRefreshes)
        assertEquals(1, sessionRefreshes)
        compose.onNodeWithText("缓存命中").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("40.0%").assertIsDisplayed()
        compose.onNodeWithText("压缩上下文").performScrollTo().performClick()
        assertEquals(1, compactions)
        assertTrue(dismissed)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
