package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.model.*
import dev.memoh.core.network.CloudAuth
import dev.memoh.feature.chat.ChatSocketFactory
import dev.memoh.feature.chat.ChatViewModel
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookmarkIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun savedReplyAndFileSurviveReopeningTheConversation() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            val reply = UITurn("turn", id = "reply", role = "assistant", messages = listOf(
                UIMessage(0, "text", "任务结果"), UIMessage(1, "attachments", attachments = listOf(
                    UIAttachment(name = "report.pdf", path = "/data/report.pdf")))))
            server.messagesBody = json.encodeToString(ListResponse(items = listOf(reply)))
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val account = "bookmark-${System.nanoTime()}"
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    super.getSharedPreferences("$account-$name", mode)
            }
            val credentials = CredentialStore(context, json)
            credentials.saveAccount(StoredAccount(account, server.origin, "selfhosted"))
            credentials.saveToken(account, "fixture-token", null)
            val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
            val settings = SettingsStore(context)
            val root = File(context.cacheDir, account)
            fun chat() = ChatViewModel(repository, json, ChatSocketFactory(OkHttpClient(), json, CloudAuth(OkHttpClient(), json)),
                settings, ChatHistoryStore(File(root, "history"), json), AttachmentDraftStore(File(root, "drafts"), json))
            val first = chat()
            val reopened = chat()
            try {
                compose.setContent { }
                compose.runOnIdle { first.open("b", "s") }
                compose.waitUntil(10_000) { first.state.value.history.any { it.listKey == reply.listKey } }
                compose.runOnIdle { first.toggleSavedTurn(reply) }
                compose.waitUntil(5_000) { first.state.value.savedItems.size == 1 }
                compose.runOnIdle { reopened.open("b", "s") }
                compose.waitUntil(10_000) { reopened.state.value.savedItems.size == 1 }
                val saved = reopened.state.value.savedItems.single()
                assertEquals("任务结果", saved.text)
                assertEquals("/data/report.pdf", saved.attachments.single().path)
                assertEquals(reply.listKey, saved.turnKey)
                compose.runOnIdle { first.toggleSavedTurn(reply) }
                compose.waitUntil(5_000) { first.state.value.savedItems.isEmpty() && reopened.state.value.savedItems.isEmpty() }
                compose.runOnIdle { first.toggleSavedTurn(reply.copy(id = null)) }
                compose.waitUntil(5_000) { reopened.state.value.savedItems.singleOrNull()?.provisionalTurnId == "turn" }
                compose.runOnIdle { reopened.toggleSavedTurn(reply) }
                compose.waitUntil(5_000) { reopened.state.value.savedItems.isEmpty() }
            } finally {
                compose.runOnIdle { first.viewModelScope.cancel(); reopened.viewModelScope.cancel() }
            }
        }
    }
}
