package dev.memoh.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.model.Bot
import dev.memoh.core.model.Session
import dev.memoh.core.model.Workdir
import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.UIStreamEvent
import dev.memoh.core.model.SessionActivityEvent
import dev.memoh.core.network.ChatSocket
import dev.memoh.core.network.SocketStatus
import dev.memoh.core.network.SessionRunStatusReducer
import dev.memoh.core.network.sessionStatusSocket
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class SessionsUiState(
    /**
     * True only while a load is actually in flight.
     *
     * Starts false: a spinner shown before any request exists is a spinner that
     * can be left running forever by an early return, which is exactly what
     * happened when this screen relied on an external `bind()` that nothing
     * called.
     */
    val loading: Boolean = false,
    val initialized: Boolean = false,
    val sessions: List<Session> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val error: String? = null,
    val bot: Bot? = null,
    /** Bots the user can switch between; empty until loaded. */
    val bots: List<Bot> = emptyList(),
    /** The bot's folders, pinned above the session list. */
    val folders: List<Workdir> = emptyList(),
    /** Which view of the bot's content is showing. */
    val tab: BotTab = BotTab.Chats,
    /** Search query; empty means "show everything". */
    val query: String = "",
    /** Title of the session being renamed, if the dialog is open. */
    val renaming: Session? = null,
    /** Session pending a delete confirmation. */
    val deleting: Session? = null,
    val creating: Boolean = false,
    val runStates: Map<String, RuntimeState> = emptyMap(),
    val compactingSessions: Set<String> = emptySet(),
) {
    /** Sessions matching [query], if one is set. */
    private val visible: List<Session>
        get() = if (query.isBlank()) {
            sessions
        } else {
            sessions.filter { it.title?.contains(query, ignoreCase = true) == true }
        }

    /** Today's sessions sort above older ones, matching the web client. */
    val today: List<Session> get() = visible.filter { it.isFromToday() }
    val earlier: List<Session> get() = visible.filterNot { it.isFromToday() }

    /** Folders matching [query]; a folder match keeps its sessions reachable. */
    val visibleFolders: List<Workdir>
        get() = if (query.isBlank()) {
            folders
        } else {
            folders.filter { it.name.contains(query, ignoreCase = true) }
        }

    /** True when a search is running but nothing matched. */
    val searchMissed: Boolean
        get() = query.isNotBlank() && today.isEmpty() && earlier.isEmpty() && visibleFolders.isEmpty()
}

/** The bot's content, as switched by the segmented control under the app bar. */
enum class BotTab(val label: String) {
    Chats("对话"),
    Files("文件"),
    Schedules("日程"),
}

/**
 * Session list for one bot.
 *
 * Sessions are listed by `updated_at` from the server rather than sorted
 * locally, so the order matches every other client the user has open.
 *
 * The bot list is loaded here rather than pushed in by the caller: the screen
 * cannot render anything useful without a bot, and a missing push left the UI
 * spinning forever. Loading it is this ViewModel's job.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    private var botId: String? = null
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var started = false
    private var statusSocket: ChatSocket? = null
    private var observation = 0L
    private val runCursors = mutableMapOf<String, RuntimeState>()

    fun observeRuns() {
        val id = botId ?: return
        if (statusSocket != null) return
        val api = repository.api() ?: return
        val generation = repository.generation
        val attempt = ++observation
        statusSocket = api.sessionStatusSocket(id, viewModelScope, onEvent = { event ->
            viewModelScope.launch {
                if (attempt != observation || generation != repository.generation) return@launch
                val sessionId = event.sessionId ?: return@launch
                if (state.value.sessions.none { it.id == sessionId }) return@launch
                val previous = runCursors[sessionId] ?: RuntimeState.EMPTY
                val next = if (event.type == UIStreamEvent.RUNTIME_DROPPED) previous.copy(needsSnapshot = true)
                    else SessionRunStatusReducer.apply(previous, event)
                runCursors[sessionId] = next
                if (next.needsSnapshot && !previous.needsSnapshot) statusSocket?.send(dev.memoh.core.model.WSClientMessage(
                    type = dev.memoh.core.model.WSClientMessage.RUNTIME_SUBSCRIBE, sessionId = sessionId))
                // Text deltas advance the cursor without rebuilding every row in the list.
                if (next.needsSnapshot != previous.needsSnapshot || next.run?.status != previous.run?.status ||
                    next.run?.configurationOnly != previous.run?.configurationOnly)
                    _state.update { it.copy(runStates = it.runStates + (sessionId to next)) }
            }
        }, onStatus = { status ->
            if (attempt == observation && generation == repository.generation && status != SocketStatus.Connected) {
                runCursors.clear()
                _state.update { it.copy(runStates = emptyMap()) }
            }
        }).also { socket ->
            socket.observeSessions(state.value.sessions.map { it.id }.toSet())
            socket.connect()
        }
    }

    fun stopObservingRuns() {
        observation++
        statusSocket?.close(); statusSocket = null
        runCursors.clear()
        _state.update { it.copy(runStates = emptyMap(), compactingSessions = emptySet()) }
    }

    /** Loads the bot list once, then the content for the selected bot. */
    fun start() {
        if (started) return
        started = true
        loadBots()
    }

    fun selectTab(tab: BotTab) = _state.update { it.copy(tab = tab) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    private fun loadBots() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val api = repository.api()
            if (api == null) {
                _state.update { it.copy(loading = false, initialized = true, error = "未登录") }
                return@launch
            }
            runCatching { api.bots() }.fold(
                onSuccess = { bots ->
                    if (bots.isEmpty()) {
                        // Signed in, but the account has no bot yet. Not an
                        // error: the empty state explains it better than a
                        // spinner or a red bar would.
                        _state.update {
                            it.copy(loading = false, initialized = true, bots = emptyList(), bot = null, sessions = emptyList())
                        }
                        return@launch
                    }
                    val lastBot = settings.lastBotId.first()
                    val selected = bots.firstOrNull { it.id == (botId ?: lastBot) } ?: bots.first()
                    _state.update { it.copy(initialized = true, bots = bots, bot = selected) }
                    botId = selected.id
                    settings.setLastBotId(selected.id)
                    loadSessions(selected.id)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(loading = false, initialized = true, error = describeFailure("加载 Bot 失败", error))
                    }
                },
            )
        }
    }

    /** Switches the list to another bot. */
    fun selectBot(bot: Bot) {
        if (botId == bot.id) return
        stopObservingRuns()
        botId = bot.id
        _state.update { it.copy(bot = bot, sessions = emptyList(), folders = emptyList(), nextCursor = null, loadingMore = false, loadMoreError = null) }
        viewModelScope.launch { settings.setLastBotId(bot.id) }
        loadSessions(bot.id)
    }

    fun refresh() {
        val id = botId
        if (id == null) {
            loadBots()
            return
        }
        loadSessions(id)
    }

    private fun loadSessions(id: String) {
        loadJob?.cancel()
        pageJob?.cancel()
        val generation = repository.generation
        val count = maxOf(50, state.value.sessions.size)
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, loadingMore = false, loadMoreError = null) }
            val api = repository.api()
            if (api == null) {
                _state.update { it.copy(loading = false, error = "未登录") }
                return@launch
            }

            // Folders and sessions are independent: a folder list that fails
            // must not blank the sessions, and vice versa. The folder failure is
            // therefore swallowed — the pinned section simply does not appear.
            val folders = runCatching { api.workdirs(id) }.getOrDefault(emptyList())
            val sessions = runCatching {
                var page = api.sessions(id, types = Session.CHAT_TYPES, limit = 50)
                var items = page.items
                // Refresh every loaded page so returning from a chat retains older rows.
                while (items.size < count && !page.nextCursor.isNullOrBlank()) {
                    val cursor = page.nextCursor
                    page = api.sessions(id, types = Session.CHAT_TYPES, limit = 50, cursor = cursor)
                    items = appendSessions(items, page.items)
                    if (page.nextCursor == cursor) break
                }
                page.copy(items = items)
            }
            if (generation != repository.generation || botId != id) return@launch

            sessions.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            loading = false,
                            sessions = page.items,
                            nextCursor = page.nextCursor,
                            folders = folders.filter(Workdir::isActive),
                            error = null,
                        )
                    }
                    statusSocket?.observeSessions(page.items.map { it.id }.toSet())
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _state.update {
                        it.copy(
                            loading = false,
                            folders = folders.filter(Workdir::isActive),
                            error = describeFailure("加载会话失败", error),
                        )
                    }
                },
            )
        }
    }

    fun loadMore() {
        val current = state.value
        val id = botId ?: return
        val cursor = current.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (current.loading || current.loadingMore) return
        val generation = repository.generation
        _state.update { it.copy(loadingMore = true, loadMoreError = null) }
        pageJob = viewModelScope.launch {
            try {
                val api = repository.api() ?: error("未登录")
                val page = api.sessions(id, types = Session.CHAT_TYPES, limit = 50, cursor = cursor)
                if (botId != id || generation != repository.generation) return@launch
                _state.update { it.copy(sessions = appendSessions(it.sessions, page.items),
                    nextCursor = page.nextCursor?.takeUnless { next -> next == cursor }, loadingMore = false) }
                statusSocket?.observeSessions(state.value.sessions.map { it.id }.toSet())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (botId == id && generation == repository.generation)
                    _state.update { it.copy(loadingMore = false, loadMoreError = describeFailure("加载更多失败", e)) }
            }
        }
    }

    fun createSession(onCreated: (Session) -> Unit) {
        val id = botId ?: return
        if (_state.value.creating) return
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            val api = repository.api()
            if (api == null) {
                _state.update { it.copy(creating = false, error = "未登录") }
                return@launch
            }
            runCatching { api.createSession(id, title = null) }.fold(
                onSuccess = { session ->
                    _state.update {
                        it.copy(creating = false, sessions = listOf(session) + it.sessions)
                    }
                    statusSocket?.observeSessions(state.value.sessions.map { it.id }.toSet())
                    onCreated(session)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(creating = false, error = describeFailure("新建会话失败", error))
                    }
                },
            )
        }
    }

    fun beginRename(session: Session) = _state.update { it.copy(renaming = session) }

    fun cancelRename() = _state.update { it.copy(renaming = null) }

    fun rename(session: Session, title: String) {
        val id = botId ?: return
        _state.update { it.copy(renaming = null) }
        viewModelScope.launch {
            val api = repository.api() ?: return@launch
            runCatching { api.renameSession(id, session.id, title) }.fold(
                onSuccess = { updated ->
                    _state.update { current ->
                        current.copy(
                            sessions = current.sessions.map {
                                if (it.id == updated.id) updated else it
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(error = describeFailure("重命名失败", error)) }
                },
            )
        }
    }

    fun beginDelete(session: Session) = _state.update { it.copy(deleting = session) }

    fun cancelDelete() = _state.update { it.copy(deleting = null) }

    fun delete(session: Session) {
        val id = botId ?: return
        val generation = repository.generation
        _state.update { it.copy(deleting = null) }
        viewModelScope.launch {
            val api = repository.api() ?: return@launch
            runCatching { api.deleteSession(id, session.id) }.fold(
                onSuccess = {
                    if (botId != id || generation != repository.generation) return@fold
                    // An older list response must not put the deleted row back.
                    loadJob?.cancel()
                    pageJob?.cancel()
                    _state.update { current ->
                        current.copy(sessions = current.sessions.filterNot { it.id == session.id },
                            loading = false, loadingMore = false)
                    }
                    statusSocket?.observeSessions(state.value.sessions.map { it.id }.toSet())
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    if (botId != id || generation != repository.generation) return@fold
                    _state.update { it.copy(error = describeFailure("删除失败", error)) }
                },
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun onActivity(event: SessionActivityEvent) {
        when (event.type) {
            SessionActivityEvent.SESSION_TITLE_CHANGED -> event.sessionId?.let { id ->
                event.title?.let { applyTitleChange(id, it) }
            }
            SessionActivityEvent.SESSION_TOUCHED -> event.sessionId?.let { id -> touch(id, event.updatedAt) }
            SessionActivityEvent.SESSION_COMPACTION -> _state.update { it.copy(compactingSessions = event.sessionIds.orEmpty().toSet()) }
            SessionActivityEvent.SESSION_CREATED, SessionActivityEvent.DROPPED -> refresh()
        }
    }

    /**
     * Builds a user-facing failure line.
     *
     * Many IO exceptions carry no message, and a bare "加载失败" would hide the
     * real cause; the type name is a poor label but an honest one.
     */
    private fun describeFailure(prefix: String, error: Throwable): String {
        val detail = error.message?.takeIf(String::isNotBlank)
            ?: error::class.java.simpleName
        val status = (error as? dev.memoh.core.network.ApiException)?.status
        return if (status != null) "$prefix（HTTP $status）：$detail" else "$prefix：$detail"
    }

    /** Applies a title change pushed over the session-activity stream. */
    fun applyTitleChange(sessionId: String, title: String) {
        _state.update { current ->
            current.copy(
                sessions = current.sessions.map {
                    if (it.id == sessionId) it.copy(title = title) else it
                },
            )
        }
    }

    /** Moves a touched session to the top, mirroring server-side ordering. */
    fun touch(sessionId: String, updatedAt: String? = null) {
        _state.update { current ->
            val target = current.sessions.firstOrNull { it.id == sessionId }?.let { it.copy(updatedAt = updatedAt ?: it.updatedAt) }
                ?: return@update current
            current.copy(
                sessions = listOf(target) + current.sessions.filterNot { it.id == sessionId },
            )
        }
    }
    override fun onCleared() { stopObservingRuns(); super.onCleared() }
}

/** Preserve live title/order changes when a subsequent page overlaps existing rows. */
internal fun appendSessions(current: List<Session>, page: List<Session>): List<Session> =
    (current + page).distinctBy { it.id }

/**
 * Whether a session was updated today, by local date.
 *
 * Timestamps arrive as ISO-8601 with an offset; a parse failure falls back to
 * "earlier", which is the harmless direction.
 */
internal fun Session.isFromToday(now: java.time.LocalDate = java.time.LocalDate.now()): Boolean {
    val raw = updatedAt ?: createdAt ?: return false
    return try {
        java.time.OffsetDateTime.parse(raw).toLocalDate() == now
    } catch (e: Exception) {
        try {
            java.time.Instant.parse(raw).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == now
        } catch (e2: Exception) {
            false
        }
    }
}
