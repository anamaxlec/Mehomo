package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.*
import dev.memoh.feature.sessions.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun repository(): SessionRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = Json { ignoreUnknownKeys = true }
        val credentials = CredentialStore(context, json)
        Assume.assumeTrue("Uses the emulator's signed-in Cloud account", credentials.activeAccount()?.kind == "cloud")
        return SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json, storage = credentials))
    }
    private fun bot(api: MemohApi): Bot = runBlocking {
        val bots = api.bots()
        val last = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext).lastBotId.first()
        bots.firstOrNull { it.id == last } ?: bots.first()
    }

    @Test fun newAndExistingScheduleDialogsFitNarrowScreensWithLargeText() {
        val repository = repository(); val api = requireNotNull(repository.api()); val bot = bot(api)
        val vm = BotFeatureViewModel(repository)
        compose.setContent { MemohTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                val state by vm.state.collectAsState()
                Box(Modifier.width(320.dp)) { BotFeatureScreen(state, vm) }
            }
        } }
        try {
            compose.runOnIdle { vm.open(bot.id, BotFeature.Schedules) }
            compose.waitUntil(45000) { !vm.state.value.loading }
            assertNull(vm.state.value.error)
            compose.onNodeWithText("新建日程").performClick()
            compose.waitUntil(45000) { !vm.state.value.scheduleOptionsLoading }
            compose.onNodeWithText("重复规则").assertExists()
            for (label in listOf("每隔几分钟", "每小时", "每天", "每周", "每月", "每年", "高级 Cron")) {
                compose.onNodeWithText(label).performScrollTo().performClick()
            }
            compose.onNodeWithText("取消").performClick()
            if (vm.state.value.schedules.isNotEmpty()) {
                compose.onAllNodesWithText("编辑").onFirst().performScrollTo().performClick()
                compose.waitUntil(45000) { !vm.state.value.scheduleOptionsLoading }
                compose.onNodeWithText("编辑日程").assertExists()
                compose.onNodeWithText("取消").performClick()
            }
        } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
    }

    @Test fun cloudScheduleCreateEditPauseAndHistoryRoundTrip() {
        val repository = repository(); val api = requireNotNull(repository.api()); val bot = bot(api)
        val model = runBlocking { api.models() }.firstOrNull { it.label.contains("mimo", true) && it.label.contains("2.6") && it.label.contains("flash", true) }
        assertNotNull("Uses MiMo 2.6 Flash", model)
        val created = runBlocking { api.saveSchedule(bot.id, null, apiBody("name" to "Mehomo schedule UI test", "command" to "Reply with MEHOMO_SCHEDULE_TEST_OK.",
            "pattern" to "0 9 1,15 * *", "enabled" to false, "max_calls" to 3, "max_run_seconds" to 300,
            "run_target" to "new_session", "model_id" to model!!.id)) }
        val id = requireNotNull(created.id)
        val vm = BotFeatureViewModel(repository)
        compose.setContent { MemohTheme {
            val state by vm.state.collectAsState()
            BotFeatureScreen(state.copy(schedules = state.schedules.filter { it.id == id }), vm)
        } }
        try {
            compose.runOnIdle { vm.open(bot.id, BotFeature.Schedules) }
            compose.waitUntil(45000) { !vm.state.value.loading }
            compose.onNodeWithText("编辑").performClick()
            compose.waitUntil(45000) { !vm.state.value.scheduleOptionsLoading }
            assertNull(vm.state.value.scheduleOptionsError)
            compose.onNode(hasText("Mehomo schedule UI test") and hasSetTextAction()).performTextReplacement("Mehomo schedule edited")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(45000) { !vm.state.value.busy && !vm.state.value.loading && vm.state.value.schedules.any { it.id == id && it.name == "Mehomo schedule edited" } }
            val edited = runBlocking { api.schedules(bot.id) }.first { it.id == id }
            assertEquals("0 9 1,15 * *", edited.pattern); assertEquals(false, edited.enabled); assertEquals(model!!.id, edited.modelId)
            compose.runOnIdle { vm.toggleSchedule(edited) }
            compose.waitUntil(45000) { !vm.state.value.busy && !vm.state.value.loading && vm.state.value.schedules.first { it.id == id }.enabled == true }
            compose.runOnIdle { vm.toggleSchedule(vm.state.value.schedules.first { it.id == id }) }
            compose.waitUntil(45000) { !vm.state.value.busy && !vm.state.value.loading && vm.state.value.schedules.first { it.id == id }.enabled == false }
            assertEquals(0L, runBlocking { api.schedules(bot.id) }.first { it.id == id }.currentCalls)
            compose.onNodeWithText("执行记录").performClick()
            compose.waitUntil(45000) { !vm.state.value.logsLoading }
            assertNull(vm.state.value.logsError)
            compose.onNodeWithText("暂无执行记录").assertExists()
            compose.onNodeWithText("关闭").performClick()
            val existing = vm.state.value.schedules.firstOrNull { (it.currentCalls ?: 0) > 50 }
            if (existing != null) {
                compose.runOnIdle { vm.refresh(); vm.logs(existing) }
                compose.waitUntil(45000) { !vm.state.value.loading && !vm.state.value.logsLoading }
                assertNull(vm.state.value.logsError)
                assertEquals(existing.id, vm.state.value.logSchedule?.id)
                assertNotNull(vm.state.value.logs)
                val count = vm.state.value.logs?.items.orEmpty().size
                if (count < (vm.state.value.logs?.totalCount ?: 0)) {
                    compose.runOnIdle { vm.logs(existing, more = true) }
                    compose.waitUntil(45000) { !vm.state.value.logsLoading }
                    assertNull(vm.state.value.logsError)
                    assertTrue(vm.state.value.logs?.items.orEmpty().size > count)
                }
                compose.runOnIdle { vm.closeLogs() }
            }
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("确认").performClick()
            compose.waitUntil(45000) { !vm.state.value.busy && !vm.state.value.loading && vm.state.value.schedules.none { it.id == id } }
            assertFalse(runBlocking { api.schedules(bot.id) }.any { it.id == id })
        } finally {
            compose.runOnIdle { vm.viewModelScope.cancel() }
            runBlocking { if (api.schedules(bot.id).any { it.id == id }) api.deleteSchedule(bot.id, id) }
        }
    }
}
