package dev.memoh.android

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.feature.settings.SettingsViewModel
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.SessionRepository
import dev.memoh.android.notifications.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * The single activity. Edge-to-edge is enabled so the app draws under the system
 * bars; screens apply their own insets through Scaffold.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: SettingsStore
    @Inject lateinit var repository: SessionRepository
    private var sharedContent by mutableStateOf<SharedContent?>(null)
    private var chatTarget by mutableStateOf<ChatTarget?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIntent(intent)
    }

    private fun receiveIntent(intent: Intent) {
        SharedContent.from(intent)?.let { sharedContent = it }
        val account = intent.getStringExtra("account_id")
        val bot = intent.getStringExtra("bot_id")
        val session = intent.getStringExtra("session_id")
        if (account != null && bot != null && session != null) chatTarget = ChatTarget(account, bot, session)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sharedContent?.let { outState.putString("shared_id", it.id); outState.putString("shared_text", it.text)
            outState.putStringArrayList("shared_uris", ArrayList(it.uris.map(Uri::toString))) }
        chatTarget?.let { outState.putStringArrayList("chat_target", arrayListOf(it.accountId, it.botId, it.sessionId)) }
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getString("shared_id") != null) sharedContent = SharedContent(
            savedInstanceState.getString("shared_id")!!, savedInstanceState.getString("shared_text").orEmpty(),
            savedInstanceState.getStringArrayList("shared_uris").orEmpty().map(Uri::parse))
        else if (savedInstanceState == null) receiveIntent(intent)
        savedInstanceState?.getStringArrayList("chat_target")?.takeIf { it.size == 3 }?.let { chatTarget = ChatTarget(it[0], it[1], it[2]) }
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(preferences.backgroundMonitor, repository.state) { enabled, session -> enabled && session.loggedIn }
                    .distinctUntilChanged().collect { shouldRun ->
                        val service = Intent(this@MainActivity, TaskMonitorService::class.java)
                        if (shouldRun && androidx.core.app.NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) {
                            if (!MonitorStatus.state.value.running) {
                                try { ContextCompat.startForegroundService(this@MainActivity, service) }
                                catch (e: RuntimeException) { MonitorStatus.update(false, "启动后台监控失败：${e.javaClass.simpleName}") }
                            }
                        } else {
                            stopService(service)
                            if (shouldRun) MonitorStatus.update(false, "通知权限已关闭，后台监控未运行")
                        }
                    }
            }
        }
        setContent {
            val settings: SettingsViewModel = hiltViewModel()
            val themeMode by settings.themeMode.collectAsState()
            val accent by settings.accent.collectAsState()

            val darkTheme = when (themeMode) {
                dev.memoh.core.model.ThemeMode.System -> isSystemInDarkTheme()
                dev.memoh.core.model.ThemeMode.Light -> false
                dev.memoh.core.model.ThemeMode.Dark -> true
            }

            MemohTheme(darkTheme = darkTheme, accent = accent) {
                MemohApp(sharedContent, { sharedContent = null }, chatTarget, { chatTarget = null })
            }
        }
    }
}
