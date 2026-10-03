package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.CredentialStore
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.network.managementRead
import dev.memoh.core.network.CloudAuth
import dev.memoh.core.network.exportBotBackup
import dev.memoh.core.network.importBotBackup
import dev.memoh.feature.bots.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Optional live account check. It never creates resources, sends messages or changes credentials. */
@RunWith(AndroidJUnit4::class)
class CloudManagementSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun nativePagesReadCloudAndUnchangedBotSettingsSaveAndReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = Json { ignoreUnknownKeys = true }
        val credentials = CredentialStore(context, json)
        Assume.assumeTrue("Requires the emulator's signed-in Cloud account", credentials.activeAccount()?.kind == "cloud")
        val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json, storage = credentials))
        Assume.assumeTrue(repository.state.value.loggedIn)
        val api = requireNotNull(repository.api())
        val bots = runBlocking { api.bots() }
        Assume.assumeTrue(bots.isNotEmpty())
        val lastBot = runBlocking { SettingsStore(context).lastBotId.first() }
        val bot = bots.firstOrNull { it.id == lastBot } ?: bots.first()
        val vm = ManagementViewModel(repository)
        val records = mutableListOf<JsonObject>()
        compose.setContent { MemohTheme { val state by vm.state.collectAsState(); ManagementScreen(state, vm, {}, {}, {}, {}) } }
        try {
            for (page in ManagementPage.entries) {
                compose.runOnIdle { vm.open(bot.id, page) }
                compose.waitUntil(45000) { !vm.state.value.loading && (vm.state.value.loaded || vm.state.value.error != null) }
                val state = vm.state.value
                if (state.loaded && page == ManagementPage.Account && state.cloud) {
                    val user = state.data["user"]!!.jsonObject
                    assertTrue("Cloud profile must supply a readable name", user["display_name"]!!.jsonPrimitive.content.isNotBlank() || user["username"]!!.jsonPrimitive.content.isNotBlank())
                }
                records += buildJsonObject {
                    put("page", page.name); put("loaded", state.loaded); put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                    put("sections", JsonArray(state.data.keys.map(::JsonPrimitive)))
                    put("part_errors", JsonObject(state.data.filterKeys { it.endsWith("-error") }))
                }
                state.error?.let { error ->
                    assertTrue("${page.title}: $error", error == "当前服务未提供此功能" || error == "当前账号没有此功能的管理权限")
                    compose.onNodeWithText(error).assertExists()
                }
                if (state.loaded) {
                    compose.waitForIdle()
                    Thread.sleep(350) // Let the loaded composition replace the animated skeleton before capturing.
                    compose.waitForIdle()
                    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    File(context.getExternalFilesDir(null), "cloud-${page.name}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            compose.runOnIdle { vm.open(bot.id, ManagementPage.Bot) }
            compose.waitUntil(45000) { !vm.state.value.loading }
            if (vm.state.value.loaded && vm.state.value.canManageBot) {
                compose.onNode(hasText(bot.displayName ?: bot.name) and hasClickAction()).performClick()
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(45000) { !vm.state.value.busy }
                assertNull(vm.state.value.error)
                assertEquals("已保存", vm.state.value.notice)
                val after = runBlocking { api.bot(bot.id) }
                assertEquals(bot.name, after.name); assertEquals(bot.displayName, after.displayName); assertEquals(bot.timezone, after.timezone)
                records += buildJsonObject { put("check", "native-bot-save-reread"); put("passed", true) }
            }
            val modelWire = runBlocking { api.managementRead("models") } as? JsonArray ?: JsonArray(emptyList())
            val candidates = modelWire.filter { row -> (row as? JsonObject)?.values?.filterIsInstance<JsonPrimitive>()?.any { it.content.contains("mimo", true) } == true }.map { row ->
                JsonObject(row.jsonObject.filterKeys { it in setOf("id", "model_id", "name", "enable") })
            }
            File(context.getExternalFilesDir(null), "mimo-models.json").writeText(JsonArray(candidates).toString())
            val teams = runBlocking { repository.cloudTeams() }
            assertTrue(teams.any { it.team?.teamId == repository.state.value.account?.teamId })
            records += buildJsonObject { put("check", "cloud-teams-current-membership"); put("passed", true) }
            if (bot.can("manage")) {
                // Export only the public Bot profile, then preview it in memory. Never import or persist a backup.
                val bytes = runBlocking { api.exportBotBackup(bot.id, listOf("profile"), "") }
                assertEquals('P'.code.toByte(), bytes[0]); assertEquals('K'.code.toByte(), bytes[1])
                val preview = runBlocking { api.importBotBackup("native-check.memoh.zip", bytes, "new", null, "", preview = true) }.jsonObject
                assertTrue(preview["sections"] is JsonArray)
                records += buildJsonObject { put("check", "bot-profile-backup-export-preview"); put("passed", true) }
            }
        } finally {
            File(context.getExternalFilesDir(null), "cloud-management.json").writeText(JsonArray(records).toString())
            compose.runOnIdle { vm.cancelAuthorization(); vm.viewModelScope.cancel() }
        }
    }
}
