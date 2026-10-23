package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.CloudAuth
import dev.memoh.feature.sessions.SessionsViewModel
import dev.memoh.feature.sessions.SessionsScreen
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class HomeLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun home(server: ContinuityFixture): Pair<SessionsViewModel, SettingsStore> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "home-loading-${System.nanoTime()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        val credentials = CredentialStore(context, json)
        credentials.saveAccount(StoredAccount(prefix, server.origin, "self-hosted", username = "Fixture"))
        credentials.saveToken(prefix, "fixture-token", null)
        val settings = SettingsStore(context)
        runBlocking { settings.setLastBotId("b") }
        return SessionsViewModel(SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json)), settings) to settings
    }

    private fun showHome(vm: SessionsViewModel, onCreated: (Session) -> Unit) {
        compose.setContent { MemohTheme {
            val state by vm.state.collectAsState()
            val navigation = rememberSectionNavigationState()
            Box(Modifier.fillMaxSize()) {
                SessionsScreen(state, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
                NewSessionCornerAction(state, navigation,
                    onClick = { vm.createSession(onCreated = onCreated) },
                    modifier = Modifier.align(Alignment.BottomEnd))
            }
        } }
    }

    @Test fun creatingAnAlreadyVisibleSessionKeepsOneRowAndUsesTheReturnedDetails() {
        ContinuityFixture(json).use { server ->
            val existing = Session("s", botId = "b", title = "之前的空会话", channelType = "local")
            val older = Session("older", botId = "b", title = "保留的会话", channelType = "local")
            val returned = existing.copy(title = "新建会话的详情")
            server.sessionsBody = json.encodeToString(ListResponse(items = listOf(existing, older)))
            server.createSessionBody = json.encodeToString(returned)
            val created = CopyOnWriteArrayList<Session>()
            val (vm) = home(server)
            try {
                showHome(vm, created::add)
                compose.runOnIdle { vm.start() }
                compose.waitUntil(5_000) { !vm.state.value.loading && vm.state.value.sessions.size == 2 }
                compose.onNodeWithText(older.title!!).assertIsDisplayed()
                repeat(2) { index ->
                    compose.onNodeWithContentDescription("新建会话").performClick()
                    compose.waitUntil(5_000) { created.size == index + 1 }
                    // Keep the home list composed, as it is during navigation's exit transition.
                    compose.waitForIdle()
                    assertEquals(listOf(returned, older), vm.state.value.sessions)
                    assertFalse(vm.state.value.creating)
                    compose.onNodeWithText(returned.title!!).assertIsDisplayed()
                }
                assertEquals(2, server.sessionCreates.get())
                assertEquals(listOf(returned, returned), created.toList())
            } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun aSyncThatFindsTheNewSessionBeforeItsCreateResponseCannotDuplicateIt() {
        ContinuityFixture(json).use { server ->
            val older = Session("older", botId = "b", title = "已有会话", channelType = "local")
            val returned = Session("new", botId = "b", title = "创建返回的详情", channelType = "local")
            val create = CountDownLatch(1)
            server.sessionsBody = json.encodeToString(ListResponse(items = listOf(older)))
            server.createSessionBody = json.encodeToString(returned)
            server.createSessionBlock = create
            val created = CopyOnWriteArrayList<Session>()
            val (vm) = home(server)
            try {
                showHome(vm, created::add)
                compose.runOnIdle { vm.start() }
                compose.waitUntil(5_000) { !vm.state.value.loading && vm.state.value.sessions.size == 1 }
                compose.onNodeWithContentDescription("新建会话").performClick()
                compose.waitUntil(5_000) { server.sessionCreates.get() == 1 }
                server.sessionsBody = json.encodeToString(ListResponse(items = listOf(
                    returned.copy(title = "同步先看到的新会话"), older)))
                compose.runOnIdle { vm.sync() }
                compose.waitUntil(5_000) { !vm.state.value.loading && vm.state.value.sessions.firstOrNull()?.id == returned.id }
                assertTrue(vm.state.value.creating)
                create.countDown()
                compose.waitUntil(5_000) { created.size == 1 }
                compose.waitForIdle()
                assertEquals(listOf(returned, older), vm.state.value.sessions)
                compose.onNodeWithText(returned.title!!).assertIsDisplayed()
                compose.onNodeWithText(older.title!!).assertIsDisplayed()
            } finally { create.countDown(); compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun firstPageDoesNotWaitForFolders() {
        ContinuityFixture(json).use { server ->
            val folders = CountDownLatch(1)
            server.foldersBlock = folders
            server.workdirsBody = """{"workdirs":[{"id":"project","name":"项目","path":"/data/project"}]}"""
            val (vm) = home(server)
            try {
                compose.runOnIdle { vm.start() }
                compose.waitUntil(5_000) { server.folderReads.get() > 0 && vm.state.value.sessions.isNotEmpty() }
                assertFalse(vm.state.value.loading)
                assertTrue(vm.state.value.folders.isEmpty())
                assertEquals(1, server.sessionListReads.get())
                folders.countDown()
                compose.waitUntil(5_000) { vm.state.value.folders.singleOrNull()?.id == "project" }
            } finally { folders.countDown(); compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun rememberedBotPrefetchRunsBesideTheBotCatalogue() {
        ContinuityFixture(json).use { server ->
            val bots = CountDownLatch(1)
            server.botsBlock = bots
            val (vm) = home(server)
            try {
                compose.runOnIdle { vm.start() }
                compose.waitUntil(5_000) { server.botListReads.get() > 0 && server.sessionListReads.get() > 0 }
                assertNull(vm.state.value.bot)
                assertTrue("Prefetched rows wait for the confirmed selection", vm.state.value.sessions.isEmpty())
                bots.countDown()
                compose.waitUntil(5_000) { vm.state.value.sessions.isNotEmpty() && !vm.state.value.loading }
                assertEquals(1, server.sessionListReads.get())
            } finally { bots.countDown(); compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun aRemovedRememberedBotCannotPublishItsPrefetchedRows() {
        ContinuityFixture(json).use { server ->
            val bots = CountDownLatch(1)
            server.botsBlock = bots; server.botsBody = """{"items":[]}"""
            val (vm) = home(server)
            try {
                compose.runOnIdle { vm.start() }
                compose.waitUntil(5_000) { server.sessionListReads.get() > 0 }
                bots.countDown()
                compose.waitUntil(5_000) { vm.state.value.initialized && !vm.state.value.loading }
                assertNull(vm.state.value.bot)
                assertTrue(vm.state.value.sessions.isEmpty())
                assertNull(vm.state.value.error)
            } finally { bots.countDown(); compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun refreshPublishesNewTopRowsWhileRetainingTheOlderTailAndCursor() {
        ContinuityFixture(json).use { server ->
            fun page(start: Int, end: Int, cursor: String? = null, title: String = "会话") =
                json.encodeToString(ListResponse((start until end).map { Session("s-$it", title = "$title $it") }, cursor))
            server.sessionsBody = page(0, 50, "older")
            server.laterSessionsBody = page(50, 100)
            val (vm) = home(server)
            val older = CountDownLatch(1)
            try {
                compose.runOnIdle { vm.start() }
                compose.waitUntil(5_000) { !vm.state.value.loading && vm.state.value.sessions.size == 50 }
                compose.runOnIdle { vm.loadMore() }
                compose.waitUntil(5_000) { !vm.state.value.loadingMore && vm.state.value.sessions.size == 100 }
                server.sessionsBody = page(0, 50, "older", "已更新")
                server.laterSessionsBlock = older
                compose.runOnIdle { vm.sync() }
                compose.waitUntil(5_000) { vm.state.value.sessions.firstOrNull()?.title == "已更新 0" }
                assertTrue(vm.state.value.loading)
                assertFalse(vm.state.value.refreshing)
                assertEquals("s-99", vm.state.value.sessions.last().id)
                assertNull(vm.state.value.nextCursor)
                older.countDown()
                compose.waitUntil(5_000) { !vm.state.value.loading }
                assertEquals(100, vm.state.value.sessions.size)
                assertEquals(100, vm.state.value.sessions.map { it.id }.distinct().size)
                assertNull(vm.state.value.nextCursor)
            } finally { older.countDown(); compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun delayedFoldersCannotOverwriteTheNextBot() {
        ContinuityFixture(json).use { server ->
            val folders = CountDownLatch(1)
            server.foldersBlock = folders
            server.workdirsBody = """{"workdirs":[{"id":"old-project","name":"旧 Bot 项目"}]}"""
            val (vm) = home(server)
            try {
                compose.runOnIdle { vm.start() }
                compose.waitUntil(5_000) { server.folderReads.get() > 0 && !vm.state.value.loading }
                compose.runOnIdle { vm.selectBot(Bot("other", "另一个 Bot")) }
                compose.waitUntil(5_000) { vm.state.value.bot?.id == "other" && !vm.state.value.loading }
                folders.countDown()
                compose.runOnIdle {
                    assertTrue(vm.state.value.folders.isEmpty())
                    assertTrue(vm.state.value.sessions.isEmpty())
                }
            } finally { folders.countDown(); compose.runOnIdle { vm.viewModelScope.cancel() } }
        }
    }
}
