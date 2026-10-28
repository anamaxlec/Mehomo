package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.AttachmentDraftStore
import dev.memoh.core.data.ChatHistoryStore
import dev.memoh.core.data.CredentialStore
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.StoredAccount
import dev.memoh.core.model.ChatAttachment
import dev.memoh.core.network.CloudAuth
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatSocketFactory
import dev.memoh.feature.chat.ChatViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises local fake attachments against a local WebSocket fixture only. */
@RunWith(AndroidJUnit4::class)
class LargeAttachmentRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun oversizedMessageRetainsDraftAndAttachmentsWithoutReconnecting() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        ContinuityFixture(json).use { server ->
            server.clearHistory()
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val account = "large-draft-${System.nanoTime()}-"
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    super.getSharedPreferences(account + name, mode)
            }
            val credentials = CredentialStore(context, json)
            credentials.saveAccount(StoredAccount(account, server.origin, "self-hosted", username = "Fixture"))
            credentials.saveToken(account, "fixture-token", null)
            val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
            val root = File(context.cacheDir, account)
            val drafts = AttachmentDraftStore(File(root, "drafts"), json)
            // Two valid data URLs representing files below the per-file and total attachment limits.
            val data = "data:application/octet-stream;base64," + "A".repeat(8 * 1024 * 1024 + 4096)
            val attachments = listOf(
                ChatAttachment("file", data, "application/octet-stream", "first.bin"),
                ChatAttachment("file", data, "application/octet-stream", "second.bin"),
            )
            drafts.save(account, "", "b", "s", attachments)
            val settings = SettingsStore(context)
            val vm = ChatViewModel(repository, json, ChatSocketFactory(OkHttpClient(), json, CloudAuth(OkHttpClient(), json)),
                settings, ChatHistoryStore(File(root, "history"), json), drafts)
            try {
                compose.setContent { }
                compose.runOnIdle { vm.open("b", "s") }
                compose.waitUntil(20_000) { vm.state.value.canSend && vm.state.value.attachments.size == 2 }
                compose.runOnIdle {
                    vm.setDraft("核对附件")
                    vm.send("核对附件")
                }
                val state = vm.state.value
                assertEquals("消息或附件过大，请减少附件或缩短文字后重试。", state.error)
                assertEquals("核对附件", state.draft)
                assertEquals(attachments, state.attachments)
                assertTrue(state.pending.isEmpty())
                assertEquals(SocketStatus.Connected, state.socketStatus)
                assertEquals(1, server.connections.get())
                assertEquals(0, server.messageRequests.get())
                assertEquals(attachments, runBlocking { drafts.read(account, "", "b", "s") })
                compose.waitUntil(5_000) { runBlocking { settings.draft(account, "b", "s") == "核对附件" } }
                compose.runOnIdle {
                    vm.removeAttachment(0)
                    vm.send(vm.state.value.draft)
                }
                compose.waitUntil(20_000) { server.messageRequests.get() == 1 }
                assertEquals("核对附件", server.sentMessages.single().text)
                assertEquals("second.bin", server.sentMessages.single().attachments?.single()?.name)
                assertEquals(SocketStatus.Connected, vm.state.value.socketStatus)
            } finally {
                compose.runOnIdle { vm.viewModelScope.cancel() }
            }
        }
    }
}
