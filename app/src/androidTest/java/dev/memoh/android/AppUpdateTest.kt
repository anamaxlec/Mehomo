package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.network.AppRelease
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun versionAndManualCheckReadThePublicGitHubRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain -> requests.incrementAndGet(); chain.proceed(chain.request()) }.build()
        val vm = AppUpdateViewModel(context, SettingsStore(context), client, Json)
        compose.setContent { MemohTheme { val state by vm.state.collectAsState()
            AppUpdateSettings(state, true, {}, { vm.check(manual = true) }, {}, {})
        } }
        try {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            compose.onNodeWithText("Mehomo $version").assertExists()
            compose.onNodeWithText("检查更新").performClick()
            compose.waitUntil(45000) { vm.state.value.status != UpdateStatus.Checking && vm.state.value.status != UpdateStatus.Idle }
            assertNotEquals(vm.state.value.error, UpdateStatus.Error, vm.state.value.status)
            assertNotNull(vm.state.value.release)
            assertTrue(vm.state.value.release!!.pageUrl.startsWith("https://github.com/anamaxlec/Mehomo/releases/"))
            assertEquals(1, requests.get())
            compose.runOnIdle { vm.check(); vm.check() }
            Thread.sleep(300)
            assertEquals("Automatic checks respect the last successful check", 1, requests.get())
        } finally { compose.runOnIdle { vm.viewModelScope.cancel() } }
    }
    @Test fun releaseReminderShowsNotesAndOpensTheSignedApk() {
        var open = ""; var visible by mutableStateOf(true)
        val url = "https://github.com/anamaxlec/Mehomo/releases/download/v0.1.12/Mehomo-v0.1.12-release.apk"
        val release = AppRelease("0.1.12", "Mehomo 0.1.12", "更新日程与登录动效", "https://github.com/anamaxlec/Mehomo/releases/tag/v0.1.12", url, 20_000_000)
        compose.setContent { MemohTheme { CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { open = uri } }) {
            AppUpdateReminder(AppUpdateState("0.1.11", UpdateStatus.Available, release, showReminder = visible), { visible = false })
        } } }
        compose.onNodeWithText("发现新版本 0.1.12").assertExists()
        compose.onNodeWithText("当前版本 0.1.11").assertExists()
        compose.onNodeWithText("下载正式版").performClick()
        assertEquals(url, open)
        compose.onNodeWithText("发现新版本 0.1.12").assertDoesNotExist()
    }
    @Test fun retryAndAutomaticPreferenceRemainAvailableAfterNetworkFailure() {
        var checked = false; var automatic by mutableStateOf(true)
        compose.setContent { MemohTheme {
            AppUpdateSettings(AppUpdateState("0.1.12", UpdateStatus.Error, error = "检查更新失败，请检查网络后重试"), automatic,
                { automatic = it }, { checked = true }, {}, {})
        } }
        compose.onNodeWithText("检查更新失败，请检查网络后重试").assertExists()
        compose.onNode(isToggleable()).performClick(); assertFalse(automatic)
        compose.onNodeWithText("检查更新").performClick(); assertTrue(checked)
    }
}
