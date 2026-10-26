package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
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
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Local server and isolated preferences; no user account or live provider is used. */
@RunWith(AndroidJUnit4::class)
class UpstreamOctober26Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scale = mutableFloatStateOf(1f)
    private val dark = mutableStateOf(false)
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(value: String) = json.parseToJsonElement(value).jsonObject

    private inner class Fixture(empty: Boolean = false, legacy: Boolean = false) : AutoCloseable {
        val server = ContinuityFixture(json)
        val requests = CopyOnWriteArrayList<String>()
        val modelWrites = CopyOnWriteArrayList<JsonObject>()
        val providerWrites = CopyOnWriteArrayList<JsonObject>()
        val creates = AtomicInteger()
        val deletes = AtomicInteger()
        val catalogStatus = AtomicInteger(200)
        val connectionStatus = AtomicReference("ok")
        val importGate = AtomicReference<CountDownLatch?>(null)
        val deleted = AtomicBoolean(false)
        val model = obj(if (legacy)
            """{"id":"m","provider_id":"p","model_id":"fixture-model","name":"Fixture Model","type":"chat","enable":true,"config":{"context_window":128000,"compatibilities":["reasoning","tool-call"],"thinking_mode":"adaptive","reasoning_efforts":["low","high"],"future":true}}"""
            else """{"id":"m","provider_id":"p","model_id":"fixture-model","name":"Fixture Model","type":"chat","enable":true,"config":{"schema_version":2,"context_window":128000,"modalities":{"input":["text","image","pdf"],"output":["text"]},"tool_call":true,"reasoning":{"efforts":["low","high"],"toggle":true,"budget":{"min":0,"max":4096},"default":{"enabled":false,"effort":"high","budget":512}},"future":true,"compatibilities":[],"thinking_mode":"none"}}""")
        val models = AtomicReference(if (empty) emptyList() else listOf(model))
        val providers = AtomicReference(if (empty) emptyList() else listOf(obj("""{"id":"p","name":"DeepSeek","client_type":"openai-completions","enable":true,"metadata":{"source":"deepseek","catalog":"deepseek"},"config":{"base_url":"https://api.deepseek.com"}}""")))
        val vm: ManagementViewModel
        init {
            server.httpOverride = { method, target, body ->
                val path = target.substringBefore('?').removePrefix("/api")
                requests += "$method $target"
                when {
                    path == "/models" -> 200 to JsonArray(models.get()).toString()
                    path == "/models/m" && method == "PUT" -> {
                        val saved = obj(body); modelWrites += saved
                        models.set(models.get().map { if (it["id"] == JsonPrimitive("m")) JsonObject(it + saved) else it })
                        200 to "{}"
                    }
                    path == "/providers" && method == "POST" -> {
                        creates.incrementAndGet()
                        val saved = obj(body); providerWrites += saved
                        val created = JsonObject(saved + ("id" to JsonPrimitive("p")))
                        providers.set(listOf(created)); 200 to created.toString()
                    }
                    path == "/providers" -> 200 to JsonArray(providers.get()).toString()
                    path == "/provider-templates" -> 200 to "[]"
                    path == "/providers/p/test" -> 200 to """{"status":"${connectionStatus.get()}","reachable":true}"""
                    path == "/providers/p/oauth/authorize" -> 200 to """{"auth_url":"https://fixture.example/authorize"}"""
                    path == "/providers/p/oauth/status" -> 200 to """{"has_token":true,"expired":false}"""
                    path == "/providers/catalog/models" -> if (catalogStatus.get() != 200) catalogStatus.get() to """{"code":"provider.catalog_unavailable"}"""
                        else 200 to """{"source":"deepseek","stale":true,"models":[${model}]}"""
                    path in listOf("/providers/p/import-catalog-models", "/providers/p/import-models") -> {
                        importGate.get()?.await(15, TimeUnit.SECONDS)
                        if (path.endsWith("import-catalog-models") && catalogStatus.get() != 200) catalogStatus.get() to """{"code":"provider.catalog_unavailable"}"""
                        else {
                            if (models.get().isEmpty()) models.set(listOf(JsonObject(model + ("enable" to JsonPrimitive(false)))))
                            200 to """{"created":1,"updated":0,"skipped":0,"models":["m"],"catalog":{"stale":false}}"""
                        }
                    }
                    path == "/bots/b/compaction/logs" -> {
                        val offset = URI("http://fixture$target").query.split('&').first { it.startsWith("offset=") }.substringAfter('=').toInt()
                        val logs = (offset until minOf(offset + 20, 21)).map { index -> buildJsonObject {
                            put("id", "log-$index"); put("status", "ok"); put("message_count", 40)
                            put("started_at", "2026-10-26T01:00:00Z")
                            put("summary", if (index == 0) "摘要🙂 ".repeat(8_000) else "第 $index 次压缩摘要")
                        } }
                        200 to buildJsonObject { put("items", JsonArray(logs)); put("total_count", 21) }.toString()
                    }
                    path == "/bots/b/settings" -> 200 to """{"chat_model_id":"m"}"""
                    path == "/bots/b" && method == "DELETE" -> { deletes.incrementAndGet(); deleted.set(true); 200 to "{}" }
                    path == "/bots" && deleted.get() -> 200 to """{"items":[]}"""
                    else -> null
                }
            }
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val prefix = "models-v2-${System.nanoTime()}-"
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

    private fun show(fixture: Fixture, page: ManagementPage = ManagementPage.Models) {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.floatValue)) {
                MemohTheme(darkTheme = dark.value) {
                    val state by fixture.vm.state.collectAsState()
                    ManagementScreen(state, fixture.vm, {}, { fixture.vm.open("b", it) }, {}, {})
                }
            }
        }
        compose.runOnIdle { fixture.vm.open("b", page) }
        compose.waitUntil(10_000) { fixture.vm.state.value.loaded && !fixture.vm.state.value.loading }
        assertNull(fixture.vm.state.value.error)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun connectDeepSeek(beforeContinue: () -> Unit = {}) {
        compose.onNodeWithText("接入服务商").performClick()
        screenshot("providers-source-picker")
        compose.onNodeWithText("查找服务商").performTextInput("DeepSeek")
        compose.onNode(hasText("DeepSeek") and !hasSetTextAction()).performClick()
        compose.onNodeWithText("API 地址").assertDoesNotExist()
        compose.onNodeWithText("模型目录来源（可选）").assertDoesNotExist()
        compose.onNodeWithText("API key").performTextInput("fixture-key")
        screenshot("provider-connect-key")
        beforeContinue()
        compose.onNodeWithText("继续").performClick()
    }

    @Test fun canonicalModelEditsPreserveHiddenBudgetsAndAdvancedValues() = Fixture().use { fixture ->
        show(fixture)
        compose.onNodeWithText("全部模型").performScrollTo().performClick()
        compose.onNodeWithText("Fixture Model").performScrollTo().performClick()
        compose.onNodeWithText("显示名称").performTextReplacement("Edited Model")
        compose.onNodeWithText("输入", substring = false).performScrollTo().assertIsDisplayed()
        screenshot("model-v2-capabilities")
        compose.onNodeWithText("高级推理选项").performScrollTo().performClick()
        compose.onNodeWithText("推理预算上限").performScrollTo().assertTextContains("4096")
        screenshot("model-v2-reasoning")
        compose.onNodeWithText("保存", substring = false).performClick()
        compose.waitUntil(10_000) { fixture.modelWrites.size == 1 && !fixture.vm.state.value.busy }
        val config = fixture.modelWrites.single()["config"]!!.jsonObject
        assertEquals(2, config["schema_version"]!!.jsonPrimitive.int)
        assertEquals(512, config["reasoning"]!!.jsonObject["default"]!!.jsonObject["budget"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(true), config["future"])
        assertFalse("thinking_mode" in config); assertFalse("compatibilities" in config)
    }

    @Test fun oldServerModelsKeepTheLegacyEditorAndWriteFormat() = Fixture(legacy = true).use { fixture ->
        show(fixture)
        compose.onNodeWithText("全部模型").performScrollTo().performClick()
        compose.onNodeWithText("Fixture Model").performScrollTo().performClick()
        compose.onNodeWithText("思考机制").performScrollTo().assertIsDisplayed()
        screenshot("model-legacy-editor")
        compose.onNodeWithText("保存", substring = false).performClick()
        compose.waitUntil(10_000) { fixture.modelWrites.size == 1 && !fixture.vm.state.value.busy }
        val config = fixture.modelWrites.single()["config"]!!.jsonObject
        assertFalse("schema_version" in config)
        assertEquals("adaptive", config["thinking_mode"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), config["future"])
    }

    @Test fun aSubscriptionContinuesToModelSelectionAfterAuthorization() = Fixture(empty = true).use { fixture ->
        show(fixture)
        compose.onNodeWithText("接入服务商").performClick()
        compose.onNodeWithText("查找服务商").performTextInput("Codex")
        compose.onNodeWithText("OpenAI Codex", substring = false).performClick()
        compose.onNodeWithText("API key", substring = false).assertDoesNotExist()
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(10_000) { fixture.vm.state.value.result?.jsonObject?.get("auth_url") != null }
        compose.mainClock.advanceTimeBy(5_100)
        compose.waitUntil(10_000) { fixture.requests.any { it.endsWith("/import-models") } && !fixture.vm.state.value.busy }
        compose.onNodeWithText("选择模型").assertIsDisplayed()
        assertFalse(fixture.requests.any { "import-catalog-models" in it })
        assertEquals(1, fixture.creates.get())
        screenshot("subscription-model-selection")
    }

    @Test fun aSourceConnectionContinuesIntoAnExplicitModelSelection() = Fixture(empty = true).use { fixture ->
        show(fixture); connectDeepSeek()
        compose.waitUntil(10_000) { fixture.models.get().isNotEmpty() && !fixture.vm.state.value.busy }
        compose.onNodeWithText("选择模型").assertIsDisplayed()
        assertEquals(1, fixture.creates.get())
        assertEquals("deepseek", fixture.providerWrites.single()["metadata"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        assertTrue(fixture.requests.any { "import-catalog-models" in it })
        assertFalse(fixture.requests.any { it.endsWith("/import-models") })
        compose.onNodeWithText("Fixture Model").performClick()
        assertTrue(fixture.modelWrites.isEmpty())
        screenshot("provider-model-selection")
        compose.onNodeWithText("保存选择（1）").performClick()
        compose.waitUntil(10_000) { fixture.modelWrites.size == 1 && !fixture.vm.state.value.busy }
        assertTrue(fixture.models.get().single()["enable"]!!.jsonPrimitive.boolean)
        val config = fixture.modelWrites.single()["config"]!!.jsonObject
        assertFalse("compatibilities" in config); assertFalse("thinking_mode" in config)
        assertEquals(512, config["reasoning"]!!.jsonObject["default"]!!.jsonObject["budget"]!!.jsonPrimitive.int)
        compose.onNodeWithText("选择模型").assertDoesNotExist()
        compose.onNodeWithText("Fixture Model").assertDoesNotExist()
        screenshot("providers-connected")
    }

    @Test fun retryingAFailedConnectionDoesNotCreateAnotherProvider() = Fixture(empty = true).use { fixture ->
        fixture.connectionStatus.set("auth_error")
        show(fixture); connectDeepSeek()
        compose.waitUntil(10_000) { fixture.vm.state.value.error != null && !fixture.vm.state.value.busy }
        screenshot("provider-connect-failure")
        compose.onNodeWithText("连接验证失败，请检查 API key 和地址。").assertIsDisplayed()
        assertEquals(1, fixture.creates.get())
        assertFalse(fixture.requests.any { "import-catalog-models" in it })
        fixture.connectionStatus.set("ok")
        compose.onNodeWithText("获取模型").performClick()
        compose.waitUntil(10_000) { fixture.models.get().isNotEmpty() && !fixture.vm.state.value.busy }
        compose.onNodeWithText("Fixture Model").assertIsDisplayed()
        assertEquals(1, fixture.creates.get())
    }

    @Test fun aFailedCatalogNeverSilentlyUsesEndpointDiscovery() = Fixture().use { fixture ->
        fixture.catalogStatus.set(503)
        show(fixture)
        compose.onNodeWithText("DeepSeek").performClick()
        compose.onNodeWithText("重新获取").performClick()
        compose.waitUntil(10_000) { fixture.vm.state.value.error != null && !fixture.vm.state.value.busy }
        assertFalse(fixture.requests.any { it.endsWith("/import-models") })
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithContentDescription("DeepSeek操作").performClick()
        compose.onNodeWithText("从接口发现模型").performClick()
        compose.waitUntil(10_000) { fixture.requests.any { it.endsWith("/import-models") } && !fixture.vm.state.value.busy }
    }

    @Test fun anOldServerOnlyDiscoversModelsAfterAnExplicitChoice() = Fixture(empty = true).use { fixture ->
        fixture.catalogStatus.set(404)
        show(fixture); connectDeepSeek()
        compose.waitUntil(10_000) { fixture.vm.state.value.error != null && !fixture.vm.state.value.busy }
        compose.onNodeWithText("当前服务端不支持模型目录，请升级 Memoh 后重试。").assertIsDisplayed()
        assertFalse(fixture.requests.any { it.endsWith("/import-models") })
        compose.onNodeWithText("从接口发现模型").performClick()
        compose.waitUntil(10_000) { fixture.models.get().isNotEmpty() && !fixture.vm.state.value.busy }
        compose.onNodeWithText("Fixture Model").assertIsDisplayed()
        assertTrue(fixture.requests.any { it.endsWith("/import-models") })
        assertEquals(1, fixture.creates.get())
    }

    @Test fun pendingCatalogueKeepsTheProviderAndProgressVisible(): Unit = Fixture(empty = true).use { fixture ->
        val gate = CountDownLatch(1)
        fixture.importGate.set(gate)
        try {
            show(fixture); connectDeepSeek { compose.mainClock.autoAdvance = false }
            compose.waitUntil(10_000) { fixture.requests.any { "import-catalog-models" in it } }
            // Infinite progress animation is suppressed by the test clock's automatic mode.
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("选择模型").assertIsDisplayed()
            compose.onNode(hasText("DeepSeek", substring = false) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText("取消", substring = false).assertIsDisplayed()
            // Compose idleness does not include Android's native dialog window transition.
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
            compose.mainClock.advanceTimeBy(1_000)
            screenshot("provider-models-loading")
        } finally { compose.mainClock.autoAdvance = true; gate.countDown() }
        compose.waitUntil(10_000) { fixture.models.get().isNotEmpty() && !fixture.vm.state.value.busy }
        compose.onNodeWithText("Fixture Model").assertIsDisplayed()
    }

    @Test fun deletionRequiresTheExactBotUrlName() = Fixture().use { fixture ->
        show(fixture, ManagementPage.Bots)
        compose.onNodeWithContentDescription("Fixture操作").performClick()
        compose.onNodeWithText("删除 Bot", substring = false).performClick()
        compose.onNodeWithText("确认", substring = false).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("fixture")
        compose.onNodeWithText("确认", substring = false).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("Fixture ")
        compose.onNodeWithText("确认", substring = false).assertIsNotEnabled()
        assertEquals(0, fixture.deletes.get())
        compose.onNode(hasSetTextAction()).performTextReplacement("Fixture")
        screenshot("bot-delete-confirmation")
        compose.onNodeWithText("确认", substring = false).assertIsEnabled().performClick()
        compose.waitUntil(10_000) { fixture.deletes.get() == 1 && !fixture.vm.state.value.busy }
    }

    @Test fun compactionRecordsPageAndLongSummariesStayReadable() = Fixture().use { fixture ->
        show(fixture, ManagementPage.Bot)
        compose.onNodeWithText("上下文压缩", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已完成").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("已完成")[0].performClick()
        compose.onNodeWithText("压缩摘要", substring = false).assertIsDisplayed()
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("摘要🙂", substring = true)))
            .performTouchInput { swipeUp(durationMillis = 650) }
        screenshot("compaction-long-summary")
        compose.onNodeWithText("返回记录").performClick()
        compose.onNodeWithText("下一页").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("21–21 / 21").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("21–21 / 21").assertIsDisplayed()
        screenshot("compaction-records")
    }

    @Test fun theProviderPickerWorksWithLargeTextInDarkTheme() = Fixture(empty = true).use { fixture ->
        scale.floatValue = 1.4f; dark.value = true
        show(fixture)
        compose.onNodeWithText("接入服务商").performClick()
        compose.onNodeWithText("订阅服务").assertIsDisplayed()
        compose.onNodeWithText("查找服务商").performTextInput("DeepSeek")
        compose.onNode(hasText("DeepSeek") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("自定义接入").assertIsDisplayed()
        screenshot("providers-large-dark")
    }
}
