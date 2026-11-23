package dev.memoh.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.android.notifications.TaskMonitorService
import dev.memoh.core.data.CredentialStore
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.StoredAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleNotificationTest {
    @Test fun optedInMonitorPostsOnlyLiveScheduleAttentionForTheSelectedBot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Uses the isolated test app", context.packageName.endsWith(".upstream1108"))
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue("Install the isolated app with notification permission", notifications.areNotificationsEnabled())
        val frames = listOf(
            """{"type":"attention_ready"}""",
            """{"type":"session_attention","bot_id":"other","session_id":"wrong","schedule_id":"d","attention_kind":"reply","title":"Other Bot"}""",
            """{"type":"session_unread","bot_id":"b","session_id":"marked","schedule_id":"d","attention_kind":"marked","title":"Manually marked"}""",
            """{"type":"session_attention","bot_id":"b","session_id":"chat","attention_kind":"reply","title":"Ordinary chat"}""",
            """{"type":"session_attention","bot_id":"b","session_id":"run","schedule_id":"daily","attention_kind":"update","title":"Daily digest"}""",
        ).joinToString("") { "data: $it\n\n" }
        UpstreamFixture { request -> when (request.path) {
            "/users/me/attention/events" -> UpstreamReply(body = frames)
            "/bots/b/sessions" -> UpstreamReply(body = """{"items":[]}""")
            else -> UpstreamReply(404)
        } }.use { server ->
            val credentials = CredentialStore(context, Json { ignoreUnknownKeys = true })
            val account = StoredAccount("schedule-attention-fixture", server.origin, "self-hosted")
            val settings = SettingsStore(context)
            val intent = Intent(context, TaskMonitorService::class.java)
            credentials.saveAccount(account)
            credentials.saveToken(account.accountId, "fixture-only", null)
            try {
                settings.setLastBotId("b")
                settings.setBackgroundMonitor(true)
                ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use { scenario ->
                    scenario.onActivity { ContextCompat.startForegroundService(it, intent) }
                    withTimeout(15_000) {
                        while (notifications.activeNotifications.none { it.tag?.startsWith("memoh-task://schedule/") == true }) delay(100)
                    }
                    val posted = notifications.activeNotifications.single { it.tag?.startsWith("memoh-task://schedule/") == true }
                    assertTrue(posted.tag.endsWith("/b/run"))
                    assertEquals("日程有新进展", posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE))
                    assertEquals("Daily digest", posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
                    assertNotNull(posted.notification.contentIntent)
                }
            } finally {
                settings.setBackgroundMonitor(false)
                context.stopService(intent)
                credentials.removeAccount(account.accountId)
            }
        }
    }
}
