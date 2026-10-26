package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.network.*
import dev.memoh.feature.bots.*
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Channel UI uses an isolated local API; no real channel or credentials are touched. */
@RunWith(AndroidJUnit4::class)
class ChannelLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scale = mutableFloatStateOf(1f)
    private val dark = mutableStateOf(false)
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(value: String) = json.parseToJsonElement(value).jsonObject
    private inner class Fixture(owner: Boolean = true) : AutoCloseable {
        val server = ContinuityFixture(json)
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val disabled = AtomicBoolean(false)
        val vm: ManagementViewModel
        init {
            server.httpOverride = { method, target, body ->
                val path = target.substringBefore('?').removePrefix("/api")
                requests += ("$method $path" to body)
                when {
                    path == "/bots/b" -> 200 to """{"id":"b","name":"Fixture","owner_user_id":"owner","current_user_permissions":["manage"]}"""
                    path == "/users/me" -> 200 to """{"id":"${if (owner) "owner" else "member"}"}"""
                    path == "/channels" -> 200 to JsonArray(listOf("dingtalk" to "DingTalk", "discord" to "Discord", "feishu" to "Feishu", "line" to "LINE", "qq" to "QQ", "slack" to "Slack", "telegram" to "Telegram", "web" to "Web", "wechat" to "WeChat").map { (type, name) -> buildJsonObject {
                        put("type", type); put("display_name", name); put("configless", type == "web"); put("owner_only", type == "wechat")
                        put("config_schema", obj("""{"fields":{"token":{"type":"password","title":"Token"}}}"""))
                    } }).toString()
                    path == "/bots/b/channel/telegram/status" && method == "PATCH" -> {
                        disabled.set(obj(body)["disabled"]!!.jsonPrimitive.boolean); 200 to "{}"
                    }
                    path == "/bots/b/channel/telegram" && method == "GET" -> 200 to """{"disabled":${disabled.get()},"credentials":{"token":"fixture-token"}}"""
                    path in listOf("/bots/b/channel/web", "/bots/b/channel/wechat") -> 200 to """{"disabled":false,"credentials":{}}"""
                    path.startsWith("/bots/b/channel/") -> 404 to "{}"
                    else -> null
                }
            }
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val prefix = "channel-layout-${System.nanoTime()}-"
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
            }
            val store = CredentialStore(context, json)
            store.saveAccount(StoredAccount(prefix, server.origin, "self-hosted", username = "Fixture"))
            store.saveToken(prefix, "fixture-token", null)
            vm = ManagementViewModel(SessionRepository(store, json, CloudAuth(OkHttpClient(), json)))
        }
        override fun close() { vm.viewModelScope.cancel(); server.close() }
    }
    private fun show(fixture: Fixture) {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.floatValue)) {
                MemohTheme(darkTheme = dark.value) {
                    val state by fixture.vm.state.collectAsState()
                    ManagementScreen(state, fixture.vm, {}, {}, {}, {})
                }
            }
        }
        compose.runOnIdle { fixture.vm.open("b", ManagementPage.Channels) }
        compose.waitUntil(10_000) { fixture.vm.state.value.loaded && !fixture.vm.state.value.loading }
        assertNull(fixture.vm.state.value.error)
    }
    private fun screenshot(name: String, tag: String? = null) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = if (tag == null) requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            else compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun telegramSwitch() = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        and hasAnyAncestor(hasTestTag("channel-row-telegram")))

    @Test fun channelCardsHaveBalancedContentAndIndependentActions(): Unit = Fixture().use { fixture ->
        show(fixture)
        compose.onNodeWithTag("channel-row-telegram").performScrollTo()
        val row = compose.onNodeWithTag("channel-row-telegram").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Telegram", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status = compose.onNode(hasText("已连接") and hasAnyAncestor(hasTestTag("channel-row-telegram")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val toggle = telegramSwitch().fetchSemanticsNode().boundsInRoot
        assertEquals("The text block is vertically centered", row.center.y, (title.top + status.bottom) / 2, 2f)
        assertEquals("The switch shares the row center", row.center.y, toggle.center.y, 2f)
        screenshot("channels-balanced-light")
        screenshot("discord-card-fixed-test2", "channel-row-discord")
        telegramSwitch().performClick()
        compose.waitUntil(10_000) { !fixture.vm.state.value.busy && fixture.disabled.get() }
        telegramSwitch().assertIsOff()
        compose.onNodeWithText("Token", substring = false).assertDoesNotExist()
        assertEquals(1, fixture.requests.count { it.first == "PATCH /bots/b/channel/telegram/status" })
        assertFalse(fixture.requests.any { it.first == "PUT /bots/b/channel/telegram" })
        compose.onNodeWithContentDescription("Telegram操作").performClick()
        compose.onNodeWithText("断开渠道", substring = false).performClick()
        compose.onNodeWithText("断开消息渠道", substring = false).assertIsDisplayed()
        compose.onNodeWithText("取消", substring = false).performClick()
        assertFalse(fixture.requests.any { it.first.startsWith("DELETE ") })
        compose.onNodeWithTag("channel-row-discord").performScrollTo().performClick()
        compose.onNodeWithText("Token").assertIsDisplayed()
        compose.onNodeWithText("取消", substring = false).performClick()
    }

    @Test fun largeTextAndDarkThemeKeepTheChannelActionsReachable() = Fixture().use { fixture ->
        scale.floatValue = 1.5f; dark.value = true
        show(fixture)
        compose.onNodeWithTag("channel-row-telegram").performScrollTo()
        telegramSwitch().assertIsDisplayed()
        compose.onNodeWithContentDescription("Telegram操作").assertIsDisplayed()
        screenshot("channels-balanced-large-dark")
        compose.onNodeWithTag("channel-row-wechat").performScrollTo()
        compose.onNodeWithContentDescription("WeChat操作").assertIsDisplayed()
        screenshot("channels-balanced-large-bottom")
    }

    @Test fun builtInChannelsStayReadableAndOwnerOnlyChannelsStayProtected() = Fixture(owner = false).use { fixture ->
        show(fixture)
        compose.onNodeWithTag("channel-row-web").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText("Web").assertIsDisplayed()
        compose.onNodeWithTag("channel-row-wechat").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("仅 Bot 所有者可配置").assertIsDisplayed()
        compose.onNodeWithContentDescription("WeChat操作").assertIsNotEnabled()
        screenshot("channels-balanced-readonly")
        assertFalse(fixture.requests.any { !it.first.startsWith("GET ") })
    }
}
