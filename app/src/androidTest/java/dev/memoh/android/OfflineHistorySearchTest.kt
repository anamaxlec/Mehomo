package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.CloudAuth
import dev.memoh.feature.bots.OfflineHistoryScreen
import dev.memoh.feature.bots.OfflineHistoryViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineHistorySearchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cachedFileSearchReturnsASnippetAndItsSpecificMessage() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "history-search-${System.nanoTime()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$account-$name", mode)
        }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val credentials = CredentialStore(context, json)
        credentials.saveAccount(StoredAccount(account, "http://localhost", "selfhosted"))
        credentials.saveToken(account, "fixture-token", null)
        val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
        val store = ChatHistoryStore(File(context.cacheDir, account), json)
        val turn = UITurn("t", id = "reply", role = "assistant", messages = listOf(
            UIMessage(0, "attachments", attachments = listOf(UIAttachment(name = "meeting-report.pdf")))))
        runBlocking { store.save(account, "", "b", Session("s", title = "缓存会话"), listOf(turn)) }
        val vm = OfflineHistoryViewModel(repository, store)
        var selected: ConversationMatch? = null
        var selectedTurn: UITurn? = null
        compose.setContent { MemohTheme {
            val state by vm.state.collectAsState()
            OfflineHistoryScreen(state, vm, {}, { _, _, match, item -> selected = match; selectedTurn = item })
        } }
        compose.runOnIdle { vm.search("report.pdf") }
        compose.waitUntil(5_000) { vm.state.value.chats.size == 1 }
        compose.onNodeWithText("缓存会话").performScrollTo().performClick()
        assertEquals(turn.listKey, selected?.turnKey)
        assertEquals(0, selected?.messageIndex)
        assertEquals(turn, selectedTurn)
    }
}
