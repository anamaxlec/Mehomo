package dev.memoh.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
import dev.memoh.core.model.StarterPromptsResponse
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "memoh_settings")
// Saved replies may contain attachment data; draft keystrokes must not rewrite them.
private val Context.savedItemsDataStore: DataStore<Preferences> by preferencesDataStore(name = "memoh_saved_items")

/**
 * User-visible preferences, drafts and server-issued starter suggestions.
 * Conversation history is kept separately in ChatHistoryStore.
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
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val NOTIFIED_RELEASE = stringPreferencesKey("notified_release")
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

    val automaticUpdateChecks: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_UPDATE_CHECK] ?: true }
    val lastUpdateCheck: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_UPDATE_CHECK] ?: 0L }
    val notifiedRelease: Flow<String?> = context.dataStore.data.map { it[Keys.NOTIFIED_RELEASE] }
    suspend fun setAutomaticUpdateChecks(enabled: Boolean) { context.dataStore.edit { it[Keys.AUTO_UPDATE_CHECK] = enabled } }
    suspend fun recordUpdateCheck(time: Long) { context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK] = time } }
    suspend fun markReleaseNotified(version: String) { context.dataStore.edit { it[Keys.NOTIFIED_RELEASE] = version } }

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

    suspend fun starterPrompts(accountId: String, teamId: String, botId: String, locale: String): StarterPromptsResponse? {
        val raw = context.dataStore.data.first()[starterPromptsKey(accountId, teamId, botId, locale)] ?: return null
        return runCatching { Json.decodeFromString<StarterPromptsResponse>(raw) }.getOrNull()
    }

    suspend fun saveStarterPrompts(accountId: String, teamId: String, botId: String, locale: String, value: StarterPromptsResponse) {
        context.dataStore.edit { it[starterPromptsKey(accountId, teamId, botId, locale)] = Json.encodeToString(value) }
    }

    fun shareQuickTasks(accountId: String, teamId: String): Flow<List<ShareQuickTask>> = context.dataStore.data.map { prefs ->
        prefs[conversationToolsKey("share_tasks", accountId, teamId)]?.let { Json.decodeFromString<List<ShareQuickTask>>(it) }.orEmpty()
    }

    suspend fun saveShareQuickTask(accountId: String, teamId: String, task: ShareQuickTask) {
        require(task.id.isNotBlank() && task.name.isNotBlank() && task.prompt.isNotBlank() && task.botId.isNotBlank())
        val key = conversationToolsKey("share_tasks", accountId, teamId)
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let { Json.decodeFromString<List<ShareQuickTask>>(it) }.orEmpty()
            prefs[key] = Json.encodeToString(current.filterNot { it.id == task.id } + task)
        }
    }

    suspend fun removeShareQuickTask(accountId: String, teamId: String, id: String) {
        val key = conversationToolsKey("share_tasks", accountId, teamId)
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let { Json.decodeFromString<List<ShareQuickTask>>(it) }.orEmpty()
            prefs[key] = Json.encodeToString(current.filterNot { it.id == id })
        }
    }

    fun savedChatItems(accountId: String, teamId: String): Flow<List<SavedChatItem>> = context.savedItemsDataStore.data.map { prefs ->
        prefs[conversationToolsKey("saved_chat_items", accountId, teamId)]?.let { Json.decodeFromString<List<SavedChatItem>>(it) }.orEmpty()
    }

    suspend fun toggleSavedChatItem(accountId: String, teamId: String, item: SavedChatItem) {
        val key = conversationToolsKey("saved_chat_items", accountId, teamId)
        context.savedItemsDataStore.edit { prefs ->
            val current = prefs[key]?.let { Json.decodeFromString<List<SavedChatItem>>(it) }.orEmpty()
            val exists = current.any { it.botId == item.botId && it.sessionId == item.sessionId && it.turnKey == item.turnKey }
            val remaining = current.filterNot { it.botId == item.botId && it.sessionId == item.sessionId && it.turnKey == item.turnKey }
            prefs[key] = Json.encodeToString(if (exists) remaining else remaining + item)
        }
    }

    suspend fun removeSavedChatItem(accountId: String, teamId: String, item: SavedChatItem) {
        val key = conversationToolsKey("saved_chat_items", accountId, teamId)
        context.savedItemsDataStore.edit { prefs ->
            val current = prefs[key]?.let { Json.decodeFromString<List<SavedChatItem>>(it) }.orEmpty()
            prefs[key] = Json.encodeToString(current.filterNot {
                it.botId == item.botId && it.sessionId == item.sessionId && it.turnKey == item.turnKey
            })
        }
    }

    private fun conversationToolsKey(prefix: String, accountId: String, teamId: String): Preferences.Key<String> {
        fun segment(value: String) = android.util.Base64.encodeToString(value.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return stringPreferencesKey("${prefix}_${segment(accountId)}.${segment(teamId)}")
    }

    private fun starterPromptsKey(accountId: String, teamId: String, botId: String, locale: String) =
        stringPreferencesKey("starter_prompts_" + listOf(accountId, teamId, botId, locale).joinToString("_") { android.net.Uri.encode(it) })

    private fun draftKey(accountId: String, botId: String, sessionId: String) =
        stringPreferencesKey("draft_${android.net.Uri.encode(accountId)}_${android.net.Uri.encode(botId)}_${android.net.Uri.encode(sessionId)}")
}
