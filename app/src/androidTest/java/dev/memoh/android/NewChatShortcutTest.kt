package dev.memoh.android

import android.content.Intent
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.CredentialStore
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.StoredAccount
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewChatShortcutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun launcherShortcutCreatesOneConversationEvenAfterRecreation() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val account = "shortcut-new-${System.nanoTime()}"
            val credentials = CredentialStore(context, json)
            credentials.saveAccount(StoredAccount(account, server.origin, "selfhosted"))
            credentials.saveToken(account, "fixture-token", null)
            val settings = SettingsStore(context)
            runBlocking { settings.setAutomaticUpdateChecks(false); settings.setLastBotId("b") }
            val intent = Intent(context, MainActivity::class.java).setAction(LauncherShortcuts.NEW_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { activity ->
                compose.waitUntil(15_000) { server.sessionCreates.get() == 1 }
                activity.recreate()
                compose.waitForIdle()
                assertEquals(1, server.sessionCreates.get())
            }
        }
    }

    @Test fun recentShortcutOpensTheLatestSessionForTheActiveAccount() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val account = "shortcut-recent-${System.nanoTime()}"
            val credentials = CredentialStore(context, json)
            credentials.saveAccount(StoredAccount(account, server.origin, "selfhosted"))
            credentials.saveToken(account, "fixture-token", null)
            val settings = SettingsStore(context)
            runBlocking { settings.setAutomaticUpdateChecks(false); settings.setLastBotId("b") }
            LauncherShortcuts.record(context, account, "", "b", "other")
            LauncherShortcuts.record(context, account, "", "b", "s")
            val intent = Intent(context, MainActivity::class.java).setAction(LauncherShortcuts.RECENT_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(15_000) { server.historyReads.get() > 0 }
                assertEquals(0, server.sessionCreates.get())
                assertEquals("s", LauncherShortcuts.recent(context, account, "")?.sessionId)
            }
        }
    }

    @Test fun pinnedShortcutTargetOpensTheSelectedSession() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val account = "shortcut-pin-${System.nanoTime()}"
            val credentials = CredentialStore(context, json)
            credentials.saveAccount(StoredAccount(account, server.origin, "selfhosted"))
            credentials.saveToken(account, "fixture-token", null)
            val settings = SettingsStore(context)
            runBlocking { settings.setAutomaticUpdateChecks(false); settings.setLastBotId("b") }
            val intent = Intent(context, MainActivity::class.java).setAction("dev.memoh.android.action.OPEN_CHAT")
                .putExtra("account_id", account).putExtra("bot_id", "b").putExtra("session_id", "s")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(15_000) { server.historyReads.get() > 0 }
                compose.waitUntil(5_000) { LauncherShortcuts.recent(context, account, "")?.sessionId == "s" }
                assertEquals(0, server.sessionCreates.get())
            }
        }
    }

    @Test fun launcherNavigationDismissesAnUnconsumedShareSheet() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val account = "shortcut-share-${System.nanoTime()}"
            val credentials = CredentialStore(context, json)
            credentials.saveAccount(StoredAccount(account, server.origin, "selfhosted"))
            credentials.saveToken(account, "fixture-token", null)
            val settings = SettingsStore(context)
            runBlocking { settings.setAutomaticUpdateChecks(false); settings.setLastBotId("b") }
            val recentReads = java.util.concurrent.atomic.AtomicInteger()
            server.httpOverride = { method, target, _ ->
                if (method == "GET" && target.substringBefore('?') == "/bots/b/sessions/other") recentReads.incrementAndGet()
                null
            }
            val open = "dev.memoh.android.action.OPEN_CHAT"
            for ((action, targetAccount) in listOf(LauncherShortcuts.NEW_CHAT to account,
                LauncherShortcuts.RECENT_CHAT to account, open to account, open to "another-account")) {
                LauncherShortcuts.record(context, account, "", "b", "other")
                val share = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "尚未选择会话的分享内容").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ActivityScenario.launch<MainActivity>(share).use { scenario ->
                    var launched: MainActivity? = null
                    try {
                        compose.waitUntil(15_000) { compose.onAllNodesWithText("分享到会话").fetchSemanticsNodes().isNotEmpty() }
                        scenario.onActivity { activity ->
                            launched = activity
                            val navigation = Intent(context, MainActivity::class.java).setAction(action)
                            if (action == open) navigation.putExtra("account_id", targetAccount)
                                .putExtra("bot_id", "b").putExtra("session_id", "other")
                            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, navigation)
                        }
                        if (targetAccount == account) {
                            compose.waitUntil(8_000) { compose.onAllNodesWithText("分享到会话").fetchSemanticsNodes().isEmpty() }
                            if (action == LauncherShortcuts.NEW_CHAT) compose.waitUntil(15_000) { server.sessionCreates.get() == 1 }
                            else compose.waitUntil(15_000) { recentReads.get() > 0 }
                            assertTrue(compose.onAllNodesWithTag("share-target-s").fetchSemanticsNodes().isEmpty())
                        } else compose.onNodeWithText("分享到会话").assertIsDisplayed()
                        assertEquals(0, server.messageRequests.get())
                    } finally {
                        // ActivityScenario must see its original launch intent when tracking teardown.
                        InstrumentationRegistry.getInstrumentation().runOnMainSync { launched?.intent = share }
                    }
                }
            }
        }
    }
}
