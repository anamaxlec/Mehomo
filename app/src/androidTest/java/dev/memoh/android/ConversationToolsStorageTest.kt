package dev.memoh.android

import android.content.pm.ShortcutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.SavedChatItem
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.ShareQuickTask
import dev.memoh.core.model.UIAttachment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationToolsStorageTest {
    @Test fun presetsAndSavedFilesAreScopedByAccountAndTeam() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(context)
        val account = "account-${System.nanoTime()}"
        val task = ShareQuickTask("task", "整理网页", "提取来源", "bot")
        settings.saveShareQuickTask(account, "team-a", task)
        assertEquals(listOf(task), settings.shareQuickTasks(account, "team-a").first())
        assertTrue(settings.shareQuickTasks(account, "team-b").first().isEmpty())
        assertTrue(settings.shareQuickTasks("another-$account", "team-a").first().isEmpty())
        val saved = SavedChatItem("bot", "session", "assistant:one", "回复", attachments = listOf(UIAttachment(name = "report.pdf", url = "/report")))
        settings.toggleSavedChatItem(account, "team-a", saved)
        assertEquals(listOf(saved), settings.savedChatItems(account, "team-a").first())
        assertTrue(settings.savedChatItems(account, "team-b").first().isEmpty())
        settings.removeSavedChatItem(account, "team-a", saved)
        settings.removeShareQuickTask(account, "team-a", task.id)
        assertTrue(settings.savedChatItems(account, "team-a").first().isEmpty())
        assertTrue(settings.shareQuickTasks(account, "team-a").first().isEmpty())
    }

    @Test fun launcherRecentShortcutNeverReusesAnotherAccountsSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "shortcut-${System.nanoTime()}"
        val manager = context.getSystemService(ShortcutManager::class.java)
        LauncherShortcuts.record(context, account, "team-a", "bot", "session")
        val recent = manager.dynamicShortcuts.single { it.id == "recent-chat" }
        assertEquals(LauncherShortcuts.RECENT_CHAT, recent.intent?.action)
        assertEquals(ChatTarget(account, "bot", "session"), LauncherShortcuts.recent(context, account, "team-a"))
        assertTrue(manager.dynamicShortcuts.any { it.id == "new-chat" })
        LauncherShortcuts.publish(context, "$account-other", "team-a")
        assertFalse(manager.dynamicShortcuts.any { it.id == "recent-chat" })
        LauncherShortcuts.publish(context, account, "team-a")
        LauncherShortcuts.record(context, account, "team-a", "bot", "newer-session")
        assertEquals("newer-session", LauncherShortcuts.recent(context, account, "team-a")?.sessionId)
        assertEquals(LauncherShortcuts.RECENT_CHAT, manager.dynamicShortcuts.single { it.id == "recent-chat" }.intent?.action)
    }
}
