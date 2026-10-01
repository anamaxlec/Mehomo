package dev.memoh.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.model.*
import dev.memoh.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject

data class WorkspaceState(
    val botId: String = "", val surface: WorkspaceSurface = WorkspaceSurface.Terminal,
    val loading: Boolean = false, val connecting: Boolean = false, val connected: Boolean = false,
    val rendererReady: Boolean = false, val error: String? = null,
    val runtimeDisplay: Boolean = false,
    val terminalInfo: TerminalInfo? = null, val displayInfo: DisplayInfo? = null,
    val browser: BrowserSession? = null, val address: String = "localhost:5173/",
    val preparing: Boolean = false, val preparePercent: Int? = null, val prepareMessage: String? = null,
)
sealed interface WorkspaceCommand {
    data object StartDesktop : WorkspaceCommand
    data object StartRuntimeDisplay : WorkspaceCommand
    data object RuntimeOpened : WorkspaceCommand
    data class RuntimeOutput(val bytes: ByteArray) : WorkspaceCommand
    data class Answer(val value: DisplayAnswer) : WorkspaceCommand
    data object Stop : WorkspaceCommand
}

@HiltViewModel
class WorkspaceViewModel @Inject constructor(private val repository: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceState())
    val state = _state.asStateFlow()
    // The PTY may send its prompt before Compose starts collecting. A channel keeps those bytes.
    private val _output = Channel<ByteArray>(256)
    val output = _output.receiveAsFlow()
    private val _commands = Channel<WorkspaceCommand>(256)
    val commands = _commands.receiveAsFlow()
    private var job: Job? = null
    private var keepAlive: Job? = null
    private var terminal: TerminalConnection? = null
    private var runtime: RuntimeDisplayConnection? = null
    private var displaySession: String? = null
    private var epoch = 0L
    private var terminalAutoConnect = true
    private var active = false
    private var terminalCols = 80
    private var terminalRows = 24

    fun open(botId: String, surface: WorkspaceSurface) {
        if (active && state.value.botId == botId && state.value.surface == surface) return
        stop()
        _state.value = WorkspaceState(botId = botId, surface = surface)
        active = true; terminalAutoConnect = true
        refresh()
    }
    fun refresh() {
        job?.cancel()
        val current = state.value
        if (current.botId.isBlank()) return
        val attempt = epoch
        val generation = repository.generation
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = repository.api() ?: error("请先登录")
                when (current.surface) {
                    WorkspaceSurface.Terminal -> {
                        val info = api.terminalInfo(current.botId)
                        if (attempt == epoch && generation == repository.generation) {
                            _state.update { it.copy(terminalInfo = info, loading = false) }
                            autoConnectTerminal()
                        }
                    }
                    WorkspaceSurface.Desktop -> {
                        val info = api.displayInfo(current.botId)
                        if (attempt == epoch && generation == repository.generation) _state.update { it.copy(displayInfo = info, loading = false, runtimeDisplay = api.usesRuntimeDisplay) }
                    }
                    WorkspaceSurface.Browser -> _state.update { it.copy(loading = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == epoch && generation == repository.generation) _state.update { it.copy(loading = false, error = e.message ?: "工作区加载失败") } }
        }
    }
    fun rendererReady() {
        _state.update { it.copy(rendererReady = true) }
        autoConnectTerminal()
    }
    private fun autoConnectTerminal() {
        if (active && terminalAutoConnect && state.value.surface == WorkspaceSurface.Terminal &&
            state.value.rendererReady && state.value.terminalInfo?.available == true) {
            terminalAutoConnect = false; connectTerminal()
        }
    }
    fun connectTerminal(cols: Int = terminalCols, rows: Int = terminalRows) {
        if (!active || state.value.botId.isBlank() || state.value.connecting || state.value.connected) return
        val current = state.value
        val attempt = ++epoch
        val generation = repository.generation
        terminal?.close()
        _state.update { it.copy(connecting = true, error = null) }
        job = viewModelScope.launch {
            try {
                val api = repository.api() ?: error("请先登录")
                val connection = TerminalConnection(api, current.botId,
                    onConnected = { if (attempt == epoch && generation == repository.generation) _state.update { it.copy(connected = true, connecting = false) } },
                    onOutput = { data ->
                        if (attempt == epoch && generation == repository.generation && !_output.trySend(data).isSuccess) {
                            viewModelScope.launch { disconnect(); _state.update { it.copy(error = "终端输出过快，连接已停止。请重新连接。") } }
                        }
                    },
                    onClosed = { error -> if (attempt == epoch && generation == repository.generation) _state.update { it.copy(connected = false, connecting = false, error = error) } })
                terminal = connection
                connection.connect(cols, rows)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == epoch && generation == repository.generation) _state.update { it.copy(connecting = false, error = e.message ?: "终端连接失败") } }
        }
    }
    fun terminalInput(bytes: ByteArray): Boolean {
        val sent = active && state.value.surface == WorkspaceSurface.Terminal && terminal?.input(bytes) == true
        if (!sent && active) _state.update { it.copy(error = "输入没有发送，请重新连接终端") }
        return sent
    }
    fun resizeTerminal(cols: Int, rows: Int) {
        terminalCols = cols.coerceIn(1, 500); terminalRows = rows.coerceIn(1, 500)
        terminal?.resize(terminalCols, terminalRows)
    }
    fun address(value: String) { _state.update { it.copy(address = value) } }
    fun openBrowser() {
        if (!active || state.value.connecting) return
        val current = state.value
        val address = try { parseBrowserAddress(current.address) }
            catch (e: Exception) { _state.update { it.copy(error = e.message) }; return }
        disconnect()
        val attempt = epoch
        val generation = repository.generation
        _state.update { it.copy(connecting = true, error = null, address = address.display) }
        job = viewModelScope.launch {
            val api = repository.api() ?: return@launch
            try {
                val session = withContext(NonCancellable) { api.createBrowserSession(current.botId, address.port, address.path) }
                if (attempt != epoch || generation != repository.generation || !active) {
                    release(api, current.botId, session.id, null); return@launch
                }
                _state.update { it.copy(browser = session, connected = true, connecting = false) }
                keepAlive = viewModelScope.launch {
                    while (true) {
                        delay(5 * 60_000L)
                        try { api.keepBrowserSession(current.botId, session.id) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { if (attempt == epoch) _state.update { it.copy(error = "预览连接续期失败，请重新打开") }; return@launch }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == epoch) _state.update { it.copy(connecting = false, error = e.message ?: "预览连接失败") } }
        }
    }
    fun browserError(message: String) { _state.update { it.copy(error = message) } }
    fun connectDesktop() {
        if (!active || !state.value.rendererReady || state.value.connected || state.value.connecting) return
        disconnect()
        _state.update { it.copy(connecting = true, error = null) }
        if (state.value.runtimeDisplay) _commands.trySend(WorkspaceCommand.StartRuntimeDisplay)
        else _commands.trySend(WorkspaceCommand.StartDesktop)
    }
    fun runtimeRendererReady() {
        if (!active || !state.value.connecting || !state.value.runtimeDisplay || runtime != null) return
        val current = state.value
        val attempt = epoch
        val generation = repository.generation
        job = viewModelScope.launch {
            try {
                val api = repository.api() ?: error("请先登录")
                val connection = RuntimeDisplayConnection(api, current.botId,
                    onOpened = { if (attempt == epoch && generation == repository.generation) _commands.trySend(WorkspaceCommand.RuntimeOpened) },
                    onOutput = { bytes ->
                        if (attempt == epoch && generation == repository.generation && !_commands.trySend(WorkspaceCommand.RuntimeOutput(bytes)).isSuccess) {
                            viewModelScope.launch { disconnect(); _state.update { it.copy(error = "桌面画面传输中断，请重新连接") } }
                        }
                    },
                    onClosed = { message -> if (attempt == epoch && generation == repository.generation) {
                        viewModelScope.launch { disconnect(); _state.update { it.copy(error = message) } }
                    } })
                runtime = connection
                connection.connect()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == epoch) {
                _commands.trySend(WorkspaceCommand.Stop)
                _state.update { it.copy(connecting = false, error = workspaceError(e, "桌面连接失败")) }
            } }
        }
    }
    fun runtimeInput(bytes: ByteArray) { if (active && state.value.surface == WorkspaceSurface.Desktop) runtime?.input(bytes) }
    fun desktopOffer(sdp: String) {
        if (!active || !state.value.connecting || state.value.surface != WorkspaceSurface.Desktop || sdp.length > 200_000) return
        val current = state.value
        val attempt = epoch
        val generation = repository.generation
        job = viewModelScope.launch {
            val api = repository.api() ?: return@launch
            try {
                val answer = withContext(NonCancellable) { api.displayAnswer(current.botId, sdp) }
                if (attempt != epoch || generation != repository.generation || !active) {
                    release(api, current.botId, null, answer.sessionId); return@launch
                }
                displaySession = answer.sessionId
                _commands.send(WorkspaceCommand.Answer(answer))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == epoch) {
                _commands.trySend(WorkspaceCommand.Stop)
                _state.update { it.copy(connecting = false, error = workspaceError(e, "桌面连接失败")) }
            } }
        }
    }
    fun desktopStatus(status: String) {
        if (!active || state.value.surface != WorkspaceSurface.Desktop) return
        when (status) {
            "connected" -> _state.update { it.copy(connecting = false, connected = true, error = null) }
            "failed", "disconnected" -> { disconnect(); _state.update { it.copy(error = "桌面连接中断，请重新连接") } }
        }
    }
    fun prepareDesktop() {
        if (state.value.preparing || state.value.displayInfo?.prepareSupported != true) return
        disconnect()
        val current = state.value
        val attempt = epoch
        _state.update { it.copy(preparing = true, error = null) }
        job = viewModelScope.launch {
            try {
                val api = repository.api() ?: error("请先登录")
                var completed = false
                api.prepareDisplay(current.botId).collect { event ->
                    if (attempt != epoch) return@collect
                    if (event.type == "error") error(event.message ?: event.detail ?: "桌面环境准备失败")
                    if (event.type == "complete") completed = true
                    _state.update { it.copy(preparePercent = event.percent, prepareMessage = event.message ?: event.detail ?: event.step) }
                }
                if (!completed) error("准备连接已结束，请检查桌面状态")
                _state.update { it.copy(preparing = false) }; refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == epoch) _state.update { it.copy(preparing = false, error = e.message ?: "准备失败") } }
        }
    }
    fun disconnect() {
        epoch++
        job?.cancel(); job = null; keepAlive?.cancel(); keepAlive = null
        terminal?.close(); terminal = null
        runtime?.close(); runtime = null
        while (_output.tryReceive().isSuccess) { /* Drop output belonging to the closed PTY. */ }
        val current = state.value
        val display = displaySession; displaySession = null
        repository.api()?.let { release(it, current.botId, current.browser?.id, display) }
        while (_commands.tryReceive().isSuccess) { /* Discard frames belonging to the closed viewer. */ }
        _commands.trySend(WorkspaceCommand.Stop)
        _state.update { it.copy(connected = false, connecting = false, browser = null, preparing = false) }
    }
    fun stop() { active = false; disconnect(); _state.update { it.copy(rendererReady = false) } }
    fun stop(bot: String, surface: WorkspaceSurface) { if (state.value.botId == bot && state.value.surface == surface) stop() }
    private fun release(api: MemohApi, bot: String, browser: String?, display: String?) {
        if (browser == null && display == null) return
        // This short cleanup outlives navigation so the viewer is also closed when its ViewModel is cleared.
        CoroutineScope(Dispatchers.IO).launch {
            withTimeoutOrNull(10_000) {
                browser?.let { runCatching { api.closeBrowserSession(bot, it) } }
                display?.let { runCatching { api.closeDisplaySession(bot, it) } }
            }
        }
    }
    override fun onCleared() { stop(); super.onCleared() }
}

private fun workspaceError(error: Exception, fallback: String): String {
    val api = error as? ApiException ?: return error.message ?: fallback
    val detail = api.body?.let { body -> runCatching {
        val value = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
        listOf("detail", "message", "error").firstNotNullOfOrNull { key ->
            (value?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf(String::isNotBlank)
        }
    }.getOrNull() }
    return "$fallback（HTTP ${api.status}）" + (detail?.take(300)?.let { "：$it" } ?: "")
}
