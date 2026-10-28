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
    private var providerImport by mutableStateOf<dev.memoh.core.model.ProviderImport?>(null)
    private var newChatRequest by mutableStateOf<String?>(null)
    private var recentChatRequest by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIntent(intent)
    }

    private fun receiveIntent(intent: Intent) {
        if (intent.action == LauncherShortcuts.NEW_CHAT) {
            clearPendingEntry()
            newChatRequest = java.util.UUID.randomUUID().toString()
            return
        }
        if (intent.action == LauncherShortcuts.RECENT_CHAT) {
            clearPendingEntry()
            recentChatRequest = java.util.UUID.randomUUID().toString()
            return
        }
        val providerLink = if (intent.action == Intent.ACTION_VIEW) intent.dataString else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (providerLink != null && dev.memoh.core.model.ProviderImport.isLink(providerLink)) {
            try {
                val parsed = dev.memoh.core.model.ProviderImport.parseLink(providerLink)
                clearPendingEntry()
                providerImport = parsed
            }
            catch (e: IllegalArgumentException) { android.widget.Toast.makeText(this, e.message ?: "导入链接无效", android.widget.Toast.LENGTH_LONG).show() }
            intent.data = null
            intent.removeExtra(Intent.EXTRA_TEXT)
            return
        }
        SharedContent.from(intent)?.let { clearPendingEntry(); sharedContent = it }
        val account = intent.getStringExtra("account_id")
        val bot = intent.getStringExtra("bot_id")
        val session = intent.getStringExtra("session_id")
        if (account != null && bot != null && session != null) {
            val current = repository.state.value
            if (current.loggedIn && current.account?.accountId != account) {
                android.widget.Toast.makeText(this, "此会话属于其他账号，请先切换账号", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            clearPendingEntry()
            chatTarget = ChatTarget(account, bot, session)
        }
    }

    /** A new external entry replaces an unconsumed share, import or navigation request. */
    private fun clearPendingEntry() {
        sharedContent = null
        chatTarget = null
        providerImport = null
        newChatRequest = null
        recentChatRequest = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sharedContent?.let { outState.putString("shared_id", it.id); outState.putString("shared_text", it.text)
            outState.putStringArrayList("shared_uris", ArrayList(it.uris.map(Uri::toString))) }
        chatTarget?.let { outState.putStringArrayList("chat_target", arrayListOf(it.accountId, it.botId, it.sessionId)) }
        outState.putString("new_chat_request", newChatRequest)
        outState.putString("recent_chat_request", recentChatRequest)
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getString("shared_id") != null) sharedContent = SharedContent(
            savedInstanceState.getString("shared_id")!!, savedInstanceState.getString("shared_text").orEmpty(),
            savedInstanceState.getStringArrayList("shared_uris").orEmpty().map(Uri::parse))
        else if (savedInstanceState == null) receiveIntent(intent)
        savedInstanceState?.getStringArrayList("chat_target")?.takeIf { it.size == 3 }?.let { chatTarget = ChatTarget(it[0], it[1], it[2]) }
        savedInstanceState?.getString("new_chat_request")?.let { newChatRequest = it }
        savedInstanceState?.getString("recent_chat_request")?.let { recentChatRequest = it }
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.state.collect { current ->
                    LauncherShortcuts.publish(this@MainActivity, current.account?.accountId.takeIf { current.loggedIn }, current.account?.teamId)
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
                MemohApp(sharedContent, { sharedContent = null }, chatTarget, { chatTarget = null }, providerImport, { providerImport = null },
                    newChatRequest, { newChatRequest = null }, recentChatRequest, { recentChatRequest = null })
            }
        }
    }
}
