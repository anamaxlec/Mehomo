package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.designsystem.component.MemohPageTopBar
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.*
import dev.memoh.core.network.CloudAuth
import dev.memoh.feature.bots.ManagementPage
import dev.memoh.feature.sessions.BotFeature
import dev.memoh.feature.settings.SettingsViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic credentials and local preferences; no user's service is contacted. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var settings: SettingsViewModel
    private lateinit var updates: AppUpdateViewModel
    private var restore: (() -> Unit)? = null
    private val firstBot = Bot("first", "日常助理")
    private val secondBot = Bot("second", "开发助手")

    private fun setup(cloud: Boolean = false, hasBot: Boolean = true, fontScale: Float = 1f, dark: Boolean? = null) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "settings-ui-${System.nanoTime()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        val json = Json { ignoreUnknownKeys = true }
        val credentials = CredentialStore(context, json)
        credentials.saveAccount(StoredAccount(prefix, "https://demo.memoh.test", if (cloud) "cloud" else "self-hosted",
            username = "体验用户", teamId = if (cloud) "team" else null))
        credentials.saveToken(prefix, "fixture-only", null)
        val http = OkHttpClient()
        val repository = SessionRepository(credentials, json, CloudAuth(http, json))
        val store = SettingsStore(base)
        val original = runBlocking { Triple(store.themeMode.first(), store.accent.first(), store.notifyOnReplyDone.first()) }
        restore = { runBlocking {
            store.setThemeMode(original.first); store.setAccent(original.second); store.setNotifyOnReplyDone(original.third)
        } }
        settings = SettingsViewModel(store, repository)
        updates = AppUpdateViewModel(base, store, http, json)
        compose.setContent {
            val mode by settings.themeMode.collectAsState()
            val accent by settings.accent.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MemohTheme(darkTheme = dark ?: (mode == ThemeMode.Dark), accent = accent) {
                    val nav = rememberNavController()
                    val navigation = remember { SectionNavigationState(MainSection.Profile, SectionMotion.None, MainSection.Profile) }
                    var bot by remember { mutableStateOf(firstBot.takeIf { hasBot }) }
                    FloatingNavigationHost(nav, Routes.MAIN,
                        listOf(MainSection.Chats, MainSection.Terminal, MainSection.Desktop, MainSection.Profile), navigation) {
                        memohComposable(Routes.MAIN) {
                            ProfileScreen(settings, updates, bot, listOf(firstBot, secondBot), { bot = it },
                                { nav.navigate(Routes.settings(it)) })
                        }
                        memohComposable(Routes.SETTINGS, arguments = listOf(navArgument("category") { type = NavType.StringType })) { entry ->
                            val category = SettingsCategory.valueOf(entry.arguments!!.getString("category")!!)
                            SettingsCategoryScreen(category, settings, bot, { nav.popBackStack() },
                                { nav.navigate(Routes.management(bot?.id.orEmpty(), it.name)) },
                                { nav.navigate(Routes.HISTORY) },
                                { nav.navigate(Routes.feature(bot?.id.orEmpty(), it.name)) })
                        }
                        memohComposable(Routes.MANAGEMENT, arguments = listOf(navArgument("botId") { type = NavType.StringType },
                            navArgument("page") { type = NavType.StringType })) { entry ->
                            Destination("management:${entry.arguments!!.getString("page")}") { nav.popBackStack() }
                        }
                        memohComposable(Routes.FEATURE, arguments = listOf(navArgument("botId") { type = NavType.StringType },
                            navArgument("feature") { type = NavType.StringType })) { entry ->
                            Destination("feature:${entry.arguments!!.getString("feature")}") { nav.popBackStack() }
                        }
                        memohComposable(Routes.HISTORY) { Destination("history") { nav.popBackStack() } }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable private fun Destination(label: String, back: () -> Unit) {
        Scaffold(topBar = { MemohPageTopBar("设置详情", onBack = back, large = false) }) { padding ->
            Text(label, Modifier.padding(padding))
        }
    }

    private fun click(title: String) {
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText(title))
        val target = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithContentDescription(MainSection.Profile.label).fetchSemanticsNode().boundsInRoot
        // LazyColumn's viewport extends behind the floating bar. Scroll as a user
        // would before tapping a row that is visible beneath that overlay.
        val covered = target.bottom - bar.top + 24f
        if (covered > 0f) list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, covered) }
        compose.onNodeWithText(title).performClick()
    }
    private fun back() = compose.onNodeWithContentDescription("返回").performClick()
    private fun visit(title: String, destination: String) {
        click(title)
        compose.onNodeWithText(destination).assertIsDisplayed()
        back()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @After fun cleanup() {
        if (::settings.isInitialized) settings.viewModelScope.cancel()
        if (::updates.isInitialized) updates.viewModelScope.cancel()
        restore?.invoke()
    }

    @Test fun homeKeepsSixClearEntriesAndMovesAccountAndLogoutToTheirOwnPage() {
        setup(dark = false)
        val last = compose.onNodeWithText("版本与更新").getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithContentDescription(MainSection.Profile.label).getUnclippedBoundsInRoot()
        assertTrue("The default home should keep its final entry above the floating bar: $last / $bar", last.bottom <= bar.top)
        listOf("Bot 与工作空间", "模型与连接", "功能与数据", "外观与导航", "通知与后台", "版本与更新").forEach {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(it))
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Bot 设置", "主题色", "后台监控当前 Bot", "退出登录").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        capture("settings-home-light")
        click("体验用户")
        compose.onNodeWithText("个人资料").assertIsDisplayed()
        compose.onNodeWithText("退出登录").assertIsDisplayed()
        compose.onNodeWithText("Cloud 团队与额度").assertDoesNotExist()
        compose.onNode(hasText(MainSection.Profile.label) and isSelected()).assertIsDisplayed()
        capture("settings-account")
        back()
        compose.onNodeWithText("Bot 与工作空间").assertIsDisplayed()
    }

    @Test fun everyManagementFeatureAndOfflineHistoryDestinationRemainsReachable() {
        setup()
        click("Bot 与工作空间")
        listOf(ManagementPage.Bots, ManagementPage.Bot, ManagementPage.Agents, ManagementPage.Dependencies,
            ManagementPage.Workspace, ManagementPage.Computers, ManagementPage.Access, ManagementPage.Backup).forEach {
            visit(it.title, "management:${it.name}")
        }
        back()
        click("模型与连接")
        listOf(ManagementPage.Providers, ManagementPage.Connectors, ManagementPage.Channels, ManagementPage.Hooks,
            ManagementPage.Network).forEach { visit(it.title, "management:${it.name}") }
        listOf(BotFeature.Apps, BotFeature.Mcp).forEach { visit(it.title, "feature:${it.name}") }
        back()
        click("功能与数据")
        BotFeature.entries.filterNot { it == BotFeature.Apps || it == BotFeature.Mcp }.forEach {
            visit(it.title, "feature:${it.name}")
        }
        visit("离线历史", "history")
        back()
        click("版本与更新")
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
    }

    @Test fun appearanceAndNotificationChangesPersistAcrossCategoryNavigation() {
        setup()
        click("外观与导航")
        click("外观")
        compose.onNodeWithText("深色").performClick()
        compose.waitUntil { settings.themeMode.value == ThemeMode.Dark }
        click("主题色")
        val accent = MemohAccent.entries.first { it != MemohAccent.Violet }
        compose.onNodeWithText(accent.label).performClick()
        compose.waitUntil { settings.accent.value == accent }
        click("浮动菜单")
        compose.onNodeWithText("自定义浮动菜单").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        capture("settings-appearance-dark")
        back()
        click("通知与后台")
        val before = settings.notifyReplyDone.value
        click("本轮结束提醒")
        compose.waitUntil { settings.notifyReplyDone.value != before }
        back()
        click("通知与后台")
        compose.onNodeWithText("本轮结束提醒").assertIsDisplayed()
        assertEquals(!before, settings.notifyReplyDone.value)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("系统通知设置"))
        compose.onNodeWithText("系统通知设置").assertIsDisplayed()
        capture("settings-notifications-dark")
    }

    @Test fun cloudAndMissingBotKeepOnlyApplicableSettingsAtLargeFontSize() {
        setup(cloud = true, hasBot = false, fontScale = 1.3f, dark = true)
        compose.onNodeWithText("Cloud 团队").assertIsDisplayed()
        capture("settings-home-cloud-large-font")
        click("Bot 与工作空间")
        compose.onNodeWithText("Bot 设置").assertDoesNotExist()
        compose.onNodeWithText("成员与权限").assertDoesNotExist()
        visit("Bot 管理", "management:Bots")
        back()
        click("模型与连接")
        compose.onNodeWithText("网络设置").assertDoesNotExist()
        visit("供应商与能力", "management:Providers")
        back()
        click("体验用户")
        visit("Cloud 团队与额度", "management:Team")
        back()
        click("选择 Bot")
        compose.onNodeWithText(secondBot.name).performClick()
        compose.onNodeWithText(secondBot.name).assertIsDisplayed()
    }
}
