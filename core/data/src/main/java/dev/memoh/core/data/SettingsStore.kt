package dev.memoh.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.memoh.core.model.MemohAccent
import dev.memoh.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "memoh_settings")

/**
 * User-visible preferences. Deliberately small: the server is the source of
 * truth for conversation data, so nothing here is a cache of it.
 */
class SettingsStore(private val context: Context) {
    private data class DraftWrite(val accountId: String, val botId: String, val sessionId: String, val text: String)
    private val draftWrites = Channel<DraftWrite>(Channel.UNLIMITED)
    init {
        // Writes survive leaving the chat screen and retain the order of edits.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (write in draftWrites) {
                try { setDraft(write.accountId, write.botId, write.sessionId, write.text) }
                catch (e: java.io.IOException) { android.util.Log.e("DraftStore", "Unable to save draft", e) }
            }
        }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val NOTIFY_REPLY_DONE = booleanPreferencesKey("notify_reply_done")
        val NOTIFY_DECISIONS = booleanPreferencesKey("notify_decisions")
        val BACKGROUND_MONITOR = booleanPreferencesKey("background_monitor")
        val LIVE_UPDATES = booleanPreferencesKey("live_updates")
        val FLOATING_SECTIONS = stringPreferencesKey("floating_sections")
        val LAST_BOT_ID = stringPreferencesKey("last_bot_id")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { name ->
            runCatching { ThemeMode.valueOf(name) }.getOrNull()
        } ?: ThemeMode.System
    }

    val accent: Flow<MemohAccent> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACCENT]?.let { name ->
            runCatching { MemohAccent.valueOf(name) }.getOrNull()
        } ?: MemohAccent.Violet
    }

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: false }

    val notifyOnReplyDone: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.NOTIFY_REPLY_DONE] ?: true }

    val notifyOnDecisions: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.NOTIFY_DECISIONS] ?: true }
    val backgroundMonitor: Flow<Boolean> = context.dataStore.data.map { it[Keys.BACKGROUND_MONITOR] ?: false }
    val liveUpdates: Flow<Boolean> = context.dataStore.data.map { it[Keys.LIVE_UPDATES] ?: true }

    suspend fun setBackgroundMonitor(enabled: Boolean) { context.dataStore.edit { it[Keys.BACKGROUND_MONITOR] = enabled } }
    suspend fun setLiveUpdates(enabled: Boolean) { context.dataStore.edit { it[Keys.LIVE_UPDATES] = enabled } }

    val floatingSections: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.FLOATING_SECTIONS]?.split(",")?.filter { it.isNotBlank() }?.distinct()?.take(4)
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_FLOATING_SECTIONS
    }

    suspend fun setFloatingSections(sections: List<String>) {
        require(sections.isNotEmpty() && sections.size <= 4)
        context.dataStore.edit { it[Keys.FLOATING_SECTIONS] = sections.distinct().joinToString(",") }
    }

    companion object {
        val DEFAULT_FLOATING_SECTIONS = listOf("Chats", "Terminal", "Desktop", "Profile")
    }

    val lastBotId: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_BOT_ID] }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAccent(accent: MemohAccent) {
        context.dataStore.edit { it[Keys.ACCENT] = accent.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setNotifyOnReplyDone(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_REPLY_DONE] = enabled }
    }

    suspend fun setNotifyOnDecisions(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_DECISIONS] = enabled }
    }

    suspend fun setLastBotId(botId: String) {
        context.dataStore.edit { it[Keys.LAST_BOT_ID] = botId }
    }

    /**
     * Per-session composer drafts. Keyed by account, bot and session so switching
     * conversations restores the text the user was writing.
     */
    suspend fun draft(accountId: String, botId: String, sessionId: String): String? =
        context.dataStore.data.first()[draftKey(accountId, botId, sessionId)]

    fun saveDraft(accountId: String, botId: String, sessionId: String, text: String) {
        draftWrites.trySend(DraftWrite(accountId, botId, sessionId, text))
    }

    private suspend fun setDraft(accountId: String, botId: String, sessionId: String, text: String) {
        context.dataStore.edit { prefs ->
            val key = draftKey(accountId, botId, sessionId)
            if (text.isBlank()) prefs.remove(key) else prefs[key] = text
        }
    }

    private fun draftKey(accountId: String, botId: String, sessionId: String) =
        stringPreferencesKey("draft_${android.net.Uri.encode(accountId)}_${android.net.Uri.encode(botId)}_${android.net.Uri.encode(sessionId)}")
}
