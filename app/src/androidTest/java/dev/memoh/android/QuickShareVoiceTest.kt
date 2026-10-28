package dev.memoh.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.speech.RecognizerIntent
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.CredentialStore
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.StoredAccount
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QuickShareVoiceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun quickShareAndRecognitionBothProduceAnEditableDraft() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            server.modelsBody = """[{"id":"model","type":"chat"}]"""
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val account = "share-${System.nanoTime()}"
            val credentials = CredentialStore(context, json)
            credentials.saveAccount(StoredAccount(account, server.origin, "selfhosted"))
            credentials.saveToken(account, "fixture-token", null)
            val settings = SettingsStore(context)
            runBlocking {
                settings.setAutomaticUpdateChecks(false)
                settings.setLastBotId("b")
            }
            val voice = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("这是语音补充"))
            val monitor = instrumentation.addMonitor(IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                Instrumentation.ActivityResult(Activity.RESULT_OK, voice), true)
            val intent = Intent(Intent.ACTION_SEND).setClass(context, MainActivity::class.java).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://example.test/article").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ActivityScenario.launch<MainActivity>(intent).use {
                    compose.waitUntil(15_000) { compose.onAllNodesWithTag("share-target-s").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("新建快捷任务").performClick()
                    compose.onNodeWithText("名称").performTextInput("整理网页")
                    compose.onNodeWithText("提示词").performClick()
                    compose.waitForIdle()
                    compose.onNodeWithTag("quick-task-bot").performScrollTo().assertIsDisplayed()
                    File(context.getExternalFilesDir(null), "quick-task-editor-test.png").outputStream().use {
                        instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    compose.onNodeWithText("提示词").performScrollTo().performTextInput("提取核心信息")
                    compose.onNodeWithText("保存").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("整理网页").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("整理网页").performClick()
                    compose.onNodeWithContentDescription("编辑整理网页").performClick()
                    compose.onNodeWithText("提示词").performTextClearance()
                    compose.onNodeWithText("提示词").performTextInput("提取核心信息并标出来源")
                    compose.onNodeWithText("保存").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("编辑快捷任务").fetchSemanticsNodes().isEmpty() }
                    compose.onNodeWithText("整理网页").performClick()
                    File(context.getExternalFilesDir(null), "quick-share-test.png").outputStream().use {
                        compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("share-target-s").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("share-target-s").performClick()
                    compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("语音输入").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNode(hasSetTextAction()).assertTextContains("提取核心信息并标出来源", substring = true)
                        .assertTextContains("https://example.test/article", substring = true)
                    compose.onNodeWithContentDescription("语音输入").performClick()
                    compose.waitUntil(5_000) {
                        compose.onAllNodes(hasSetTextAction() and hasText("这是语音补充", substring = true)).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNode(hasSetTextAction()).assertTextContains("提取核心信息并标出来源", substring = true)
                        .assertTextContains("https://example.test/article", substring = true).assertTextContains("这是语音补充", substring = true)
                    File(context.getExternalFilesDir(null), "voice-draft-test.png").outputStream().use {
                        compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    org.junit.Assert.assertEquals(0, server.messageRequests.get())
                }
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }
}
