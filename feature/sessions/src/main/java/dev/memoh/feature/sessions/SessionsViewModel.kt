package dev.memoh.feature.sessions

import dev.memoh.core.network.userMessage

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
import dev.memoh.core.network.MemohApi
import dev.memoh.core.network.SessionRunStatusReducer
import dev.memoh.core.network.sessionStatusSocket
import dev.memoh.core.network.managementRead
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val refreshing: Boolean = false,
    val initialized: Boolean = false,
    val sessions: List<Session> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val searchingAllTitles: Boolean = false,
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
    val selecting: Boolean = false,
    val selectedSessions: Set<String> = emptySet(),
    val exampleCapabilities: Map<String, Boolean> = emptyMap(),
    val examplesLoading: Boolean = false,
    val deletingSelected: Boolean = false,
    val runStates: Map<String, RuntimeState> = emptyMap(),
    val compactingSessions: Set<String> = emptySet(),
) {
    /** Sessions matching [query], if one is set. */
    val visibleSessions: List<Session>
        get() = if (query.isBlank()) {
            sessions
        } else {
            sessions.filter { it.title?.contains(query, ignoreCase = true) == true }
        }

    /** Today's sessions sort above older ones, matching the web client. */
    val today: List<Session> get() = visibleSessions.filter { it.isFromToday() }
    val earlier: List<Session> get() = visibleSessions.filterNot { it.isFromToday() }

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
    private var examplesJob: Job? = null
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
        }, onStatus = {}).also { socket ->
            socket.observeSessions(state.value.sessions.map { it.id }.toSet())
            socket.connect()
        }
    }

    fun stopObservingRuns(clearStates: Boolean = false) {
        observation++
        statusSocket?.close(); statusSocket = null
        // Navigation and transport loss do not mean that a server task stopped.
        if (clearStates) runCursors.clear()
        _state.update { it.copy(runStates = if (clearStates) emptyMap() else it.runStates, compactingSessions = emptySet()) }
    }

    /** Initial load; returning to an initialized screen syncs without a refresh gesture. */
    fun start() {
        if (!state.value.initialized && !state.value.loading) loadBots()
    }

    fun sync() { if (!state.value.deletingSelected && state.value.initialized && !state.value.loading) loadBots() }

    fun selectTab(tab: BotTab) { if (!state.value.deletingSelected) _state.update { it.copy(tab = tab, selecting = false, selectedSessions = emptySet()) } }

    fun setQuery(query: String) { if (query.isBlank() && state.value.searchingAllTitles) stopTitleSearch(); _state.update { it.copy(query = query) } }
    fun stopTitleSearch() { pageJob?.cancel(); _state.update { it.copy(loadingMore = false, searchingAllTitles = false) } }
    fun searchAllTitles() = loadPages(all = true)

    private fun loadBots(refreshing: Boolean = false) {
        loadJob?.cancel()
        pageJob?.cancel()
        val generation = repository.generation
        _state.update { it.copy(loadingMore = false, searchingAllTitles = false) }
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, refreshing = refreshing, error = null) }
            val api = repository.api()
            if (api == null) {
                _state.update { it.copy(loading = false, refreshing = false, initialized = true, error = "未登录") }
                return@launch
            }
            runCatching { api.bots() }.fold(
                onSuccess = { receivedBots ->
                    val bots = availableBots(receivedBots)
                    currentCoroutineContext().ensureActive()
                    if (generation != repository.generation) return@launch
                    if (bots.isEmpty()) {
                        stopObservingRuns(clearStates = true); botId = null
                        // Signed in, but the account has no bot yet. Not an
                        // error: the empty state explains it better than a
                        // spinner or a red bar would.
                        _state.update {
                            it.copy(loading = false, refreshing = false, initialized = true, bots = emptyList(), bot = null, sessions = emptyList(), folders = emptyList(), nextCursor = null)
                        }
                        return@launch
                    }
                    val lastBot = settings.lastBotId.first()
                    currentCoroutineContext().ensureActive()
                    if (generation != repository.generation) return@launch
                    val selected = preferredBot(bots, botId ?: lastBot) ?: return@launch
                    val changed = botId != selected.id
                    if (changed) { stopObservingRuns(clearStates = true); examplesJob?.cancel() }
                    _state.update { it.copy(initialized = true, bots = bots, bot = selected,
                        sessions = if (changed) emptyList() else it.sessions, folders = if (changed) emptyList() else it.folders,
                        selecting = if (changed) false else it.selecting, selectedSessions = if (changed) emptySet() else it.selectedSessions,
                        exampleCapabilities = if (changed) emptyMap() else it.exampleCapabilities, examplesLoading = if (changed) false else it.examplesLoading,
                        nextCursor = if (changed) null else it.nextCursor, creating = if (changed) false else it.creating) }
                    botId = selected.id
                    settings.setLastBotId(selected.id)
                    readSessions(selected.id, api, generation)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    if (generation != repository.generation) return@launch
                    _state.update {
                        it.copy(loading = false, refreshing = false, initialized = true, error = describeFailure("加载 Bot 失败", error))
                    }
                },
            )
        }
    }

    /** Switches the list to another bot. */
    fun selectBot(bot: Bot) {
        if (bot.status == "deleting" || state.value.deletingSelected) return
        if (botId == bot.id) return
        stopObservingRuns(clearStates = true)
        examplesJob?.cancel()
        botId = bot.id
        _state.update { it.copy(bot = bot, sessions = emptyList(), folders = emptyList(), selecting = false, selectedSessions = emptySet(), exampleCapabilities = emptyMap(), examplesLoading = false, nextCursor = null, loadingMore = false, searchingAllTitles = false, loadMoreError = null) }
        viewModelScope.launch { settings.setLastBotId(bot.id) }
        loadSessions(bot.id)
    }

    fun refresh() { if (!state.value.deletingSelected) loadBots(refreshing = true) }

    private fun loadSessions(id: String) {
        loadJob?.cancel()
        pageJob?.cancel()
        val generation = repository.generation
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, refreshing = false, error = null, loadingMore = false, searchingAllTitles = false, loadMoreError = null) }
            val api = repository.api()
            if (api == null) {
                _state.update { it.copy(loading = false, refreshing = false, error = "未登录") }
                return@launch
            }
            readSessions(id, api, generation)
        }
    }

    private suspend fun readSessions(id: String, api: MemohApi, generation: Long) {
        val count = maxOf(50, state.value.sessions.size)
        // Folders and sessions are independent: a folder list that fails
        // must not blank the sessions, and vice versa. The folder failure is
        // therefore swallowed — the pinned section simply does not appear.
        val folders = try { api.workdirs(id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
        val sessions = runCatching {
            var page = api.sessions(id, types = Session.CHAT_TYPES, limit = 50, hideScheduleRuns = true)
            var items = page.items
            // Refresh every loaded page so returning from a chat retains older rows.
            while (items.size < count && !page.nextCursor.isNullOrBlank()) {
                val cursor = page.nextCursor
                page = api.sessions(id, types = Session.CHAT_TYPES, limit = 50, cursor = cursor, hideScheduleRuns = true)
                items = appendSessions(items, page.items)
                if (page.nextCursor == cursor) break
            }
            page.copy(items = items)
        }
        currentCoroutineContext().ensureActive()
        if (generation != repository.generation || botId != id) return

        sessions.fold(
            onSuccess = { page ->
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        sessions = page.items,
                        selectedSessions = it.selectedSessions.intersect(page.items.map { session -> session.id }.toSet()),
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
                        refreshing = false,
                        folders = folders.filter(Workdir::isActive),
                        error = describeFailure("加载会话失败", error),
                    )
                }
            },
        )
    }

    fun loadMore() = loadPages(all = false)

    private fun loadPages(all: Boolean) {
        val current = state.value
        val id = botId ?: return
        var cursor = current.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (current.loading || current.loadingMore) return
        val generation = repository.generation
        _state.update { it.copy(loadingMore = true, searchingAllTitles = all, loadMoreError = null) }
        pageJob = viewModelScope.launch {
            try {
                val api = repository.api() ?: error("未登录")
                val seen = mutableSetOf<String>()
                do {
                    check(seen.add(cursor)) { "服务端返回了重复分页游标，请刷新后重试" }
                    val page = api.sessions(id, types = Session.CHAT_TYPES, limit = if (all) 200 else 50, cursor = cursor, hideScheduleRuns = true)
                    if (botId != id || generation != repository.generation) return@launch
                    check(page.nextCursor != cursor) { "服务端返回了重复分页游标，请刷新后重试" }
                    _state.update { it.copy(sessions = appendSessions(it.sessions, page.items), nextCursor = page.nextCursor) }
                    statusSocket?.observeSessions(state.value.sessions.map { it.id }.toSet())
                    cursor = page.nextCursor?.takeIf(String::isNotBlank) ?: break
                } while (all)
                _state.update { it.copy(loadingMore = false, searchingAllTitles = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (botId == id && generation == repository.generation)
                    _state.update { it.copy(loadingMore = false, searchingAllTitles = false, loadMoreError = describeFailure("加载更多失败", e)) }
            }
        }
    }

    fun createSession(workdirId: String? = null, onCreated: (Session) -> Unit) {
        val id = botId ?: return
        val generation = repository.generation
        if (_state.value.creating || state.value.deletingSelected) return
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            val api = repository.api()
            if (api == null) {
                _state.update { it.copy(creating = false, error = "未登录") }
                return@launch
            }
            runCatching { api.createSession(id, title = null, workdirId = workdirId) }.fold(
                onSuccess = { session ->
                    currentCoroutineContext().ensureActive()
                    if (generation != repository.generation || botId != id) return@fold
                    _state.update {
                        it.copy(creating = false, sessions = listOf(session) + it.sessions)
                    }
                    statusSocket?.observeSessions(state.value.sessions.map { it.id }.toSet())
                    onCreated(session)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    if (generation != repository.generation || botId != id) return@fold
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
                            loading = false, refreshing = false, loadingMore = false)
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

    fun loadExampleCapabilities() {
        val id = botId ?: return
        val generation = repository.generation
        examplesJob?.cancel()
        _state.update { it.copy(examplesLoading = true) }
        examplesJob = viewModelScope.launch {
            val result = try {
                val settings = repository.api()?.managementRead("bots", id, "settings") as? kotlinx.serialization.json.JsonObject
                dev.memoh.core.model.exampleCapabilities(settings)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyMap() }
            if (generation == repository.generation && botId == id) _state.update { it.copy(exampleCapabilities = result, examplesLoading = false) }
        }
    }

    fun beginSelection(session: Session? = null) = _state.update {
        it.copy(selecting = true, selectedSessions = session?.let { selected -> setOf(selected.id) } ?: emptySet())
    }

    fun cancelSelection() { if (!state.value.deletingSelected) _state.update { it.copy(selecting = false, selectedSessions = emptySet()) } }

    fun toggleSelection(id: String) { if (!state.value.deletingSelected) _state.update {
        it.copy(selectedSessions = if (id in it.selectedSessions) it.selectedSessions - id else it.selectedSessions + id)
    } }

    fun selectVisible() { if (!state.value.deletingSelected) _state.update {
        val visible = it.visibleSessions.map { session -> session.id }.toSet()
        it.copy(selectedSessions = if (visible.all(it.selectedSessions::contains)) it.selectedSessions - visible else it.selectedSessions + visible)
    } }

    fun deleteSelected() {
        val id = botId ?: return
        val selected = state.value.selectedSessions
        if (selected.isEmpty() || state.value.deletingSelected) return
        val generation = repository.generation
        _state.update { it.copy(deletingSelected = true, error = null) }
        loadJob?.cancel(); pageJob?.cancel()
        viewModelScope.launch {
            val failed = mutableSetOf<String>()
            var failure: String? = null
            try {
                val api = repository.api() ?: error("未登录")
                for (sessionId in selected) {
                    currentCoroutineContext().ensureActive()
                    if (generation != repository.generation || botId != id) return@launch
                    try {
                        api.deleteSession(id, sessionId)
                        if (generation != repository.generation || botId != id) return@launch
                        // A refresh started by an activity event must not restore this row.
                        loadJob?.cancel(); pageJob?.cancel()
                        _state.update { it.copy(sessions = it.sessions.filterNot { session -> session.id == sessionId },
                            selectedSessions = it.selectedSessions - sessionId, runStates = it.runStates - sessionId) }
                        runCursors.remove(sessionId)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed += sessionId; failure = e.userMessage("删除失败，请重试") }
                }
                statusSocket?.observeSessions(state.value.sessions.map { it.id }.toSet())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed += state.value.selectedSessions; failure = e.userMessage("删除失败，请重试") }
            finally {
                if (generation == repository.generation && botId == id) _state.update {
                    it.copy(deletingSelected = false, selecting = failed.isNotEmpty(), selectedSessions = failed,
                        loading = false, refreshing = false, loadingMore = false, searchingAllTitles = false,
                        error = if (failed.isEmpty()) null else "${failed.size} 个会话未删除：$failure")
                }
            }
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
            SessionActivityEvent.SESSION_CREATED, SessionActivityEvent.DROPPED -> sync()
        }
    }

    /**
     * Builds a user-facing failure line.
     *
     * Many IO exceptions carry no message, and a bare "加载失败" would hide the
     * real cause; the type name is a poor label but an honest one.
     */
    private fun describeFailure(prefix: String, error: Throwable): String {
        return error.userMessage(prefix + "，请重试")
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

internal fun availableBots(bots: List<Bot>): List<Bot> = bots.filter { it.status != "deleting" }
internal fun preferredBot(bots: List<Bot>, preferredId: String?): Bot? = bots.firstOrNull { it.id == preferredId }
    ?: bots.firstOrNull { it.status == "ready" || it.status.isNullOrBlank() } ?: bots.firstOrNull()

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
