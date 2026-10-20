package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.Session
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UITurn
import dev.memoh.core.model.ListResponse
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import kotlinx.serialization.json.Json
import java.io.File

@RunWith(AndroidJUnit4::class)
class LongConversationRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun assistantRecordsSharingATurnKeepBothReplies() {
        show(listOf(
            UITurn("shared-turn", id = "assistant-first", role = "assistant",
                messages = listOf(UIMessage(0, "text", "First persisted reply"))),
            UITurn("shared-turn", id = "assistant-second", role = "assistant",
                messages = listOf(UIMessage(0, "text", "Second persisted reply"))),
        ))
        compose.onNodeWithText("First persisted reply").assertIsDisplayed()
        compose.onNodeWithText("Second persisted reply").assertIsDisplayed()
    }

    @Test fun prependingARecordSharingTheTurnKeepsCurrentToolDetailsOpen() {
        val latest = UITurn("shared-turn", id = "assistant-latest", role = "assistant",
            messages = listOf(UIMessage(0, "tool", name = "exec", toolCallId = "read-current",
                input = Json.parseToJsonElement("""{"description":"Read current output"}"""),
                output = Json.parseToJsonElement("""{"content":[{"type":"text","text":"Current tool details"}]}"""))))
        var turns by mutableStateOf(listOf(latest))
        show { turns }
        compose.onNodeWithText("Read current output").performClick()
        compose.onNodeWithText("Current tool details").assertIsDisplayed()
        compose.runOnIdle {
            turns = listOf(UITurn("shared-turn", id = "assistant-older", role = "assistant",
                messages = listOf(UIMessage(0, "text", "Older persisted reply")))) + turns
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Older persisted reply").assertIsDisplayed()
        compose.onNodeWithText("Current tool details").assertIsDisplayed()
    }

    private fun show(turns: List<UITurn>) = show { turns }

    private fun show(turns: () -> List<UITurn>) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MemohTheme {
                    ChatScreen(ChatUiState(session = Session("long-history"), history = turns(),
                        historyLoading = false, hasMoreHistory = false, awaitingSnapshot = false,
                        socketStatus = SocketStatus.Connected, modelCatalogLoading = false),
                        {}, {}, {}, {}, {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                        onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {})
                }
            }
        }
    }

    @Test fun veryLongCodeAnswerOpensAtLatestAndCanScrollBack() {
        val code = (0 until 9_000).joinToString("\n") { "val item$it = \"long conversation line $it\"" }
        show((0 until 400).map { UITurn(turnId = "older-$it", role = "user", text = "Earlier message $it") } +
            UITurn(turnId = "long-answer", role = "assistant", messages = listOf(UIMessage(0, "text",
                content = "```kotlin\n$code\n```\n\nLong answer complete"))))
        compose.onNodeWithText("Long answer complete").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Earlier message 0").assertIsDisplayed()
    }

    @Test fun veryLongMultiParagraphAnswerOpensAtLatest() {
        val text = (0 until 4_000).joinToString("\n\n") { "Long paragraph $it with a readable line of conversation history." }
        show(listOf(UITurn(turnId = "long-paragraphs", role = "assistant", messages = listOf(
            UIMessage(0, "text", content = "$text\n\nLong paragraphs complete")))))
        compose.onNodeWithText("Long paragraphs complete").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
    }

    /** Captured history stays on the test device; user content is never bundled in the APK. */
    @Test fun capturedHistoryCanOpenAndReachLatest() {
        val name = InstrumentationRegistry.getArguments().getString("historyFixture")
        assumeTrue("Supply a local historyFixture to replay a reported conversation", name != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), requireNotNull(name))
        val turns = Json { ignoreUnknownKeys = true }.decodeFromString<ListResponse<UITurn>>(file.readText()).items
        var visibleTurns by mutableStateOf(turns.takeLast(1))
        show { visibleTurns }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.runOnIdle { visibleTurns = turns }
        compose.onNodeWithContentDescription("正在加载会话").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun veryLongUserMessageOpensWithoutMeasuringOneUnboundedBubble() {
        val text = (0 until 9_000).joinToString("\n") { "Long user message line $it" }
        show(listOf(UITurn(turnId = "long-user", role = "user", text = text),
            UITurn(turnId = "latest", role = "assistant", messages = listOf(UIMessage(0, "text", content = "Latest reply")))))
        compose.onNodeWithText("Latest reply").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Long user message line 0", substring = true).assertIsDisplayed()
    }
}
