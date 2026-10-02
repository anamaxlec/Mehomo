package dev.memoh.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.RuntimeMonitorEvents
import dev.memoh.core.data.LocalRunAccepted
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dev.memoh.core.model.RunStatus
import dev.memoh.core.model.RuntimeCursor
import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.Session
import dev.memoh.core.model.UIAssistantTurn
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UIStreamEvent
import dev.memoh.core.model.UITurn
import dev.memoh.core.model.UIUserTurn
import dev.memoh.core.model.WSClientMessage
import dev.memoh.core.model.WSUserInputAnswer
import dev.memoh.core.network.*
import dev.memoh.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import dev.memoh.core.network.ChatSocket
import dev.memoh.core.network.RuntimeReducer
import dev.memoh.core.network.SocketStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import dev.memoh.feature.chat.components.CapabilityOption
import dev.memoh.feature.chat.components.ComposerCapability
import dev.memoh.feature.chat.components.ComposerIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

private fun WorkspaceTarget.composerName(): String = if (kind == "native") "云端电脑"
    else name?.takeIf(String::isNotBlank) ?: targetId

/** A locally-created turn that has not yet been confirmed by the server. */
data class PendingTurn(
    val invocationId: String,
    val text: String,
    val attachments: List<ChatAttachment> = emptyList(),
    val acceptedTurnId: String? = null,
)

data class PendingRegeneration(val invocationId: String, val turnId: String, val acceptedTurnId: String? = null)

data class ChatUiState(
    val botId: String = "",
    val session: Session? = null,
    /** Settled history, oldest first. */
    val history: List<UITurn> = emptyList(),
    val historyLoading: Boolean = true,
    val historyError: String? = null,
    val loadingMore: Boolean = false,
    val hasMoreHistory: Boolean = true,
    /** The live run, if any. */
    val runtime: RuntimeState = RuntimeState.EMPTY,
    /** Turns awaiting acceptance, rendered optimistically at the tail. */
    val pending: List<PendingTurn> = emptyList(),
    val regeneration: PendingRegeneration? = null,
    val socketStatus: SocketStatus = SocketStatus.Idle,
    /** Composer text, restored here so a rejected send is not lost. */
    val draft: String = "",
    /** Chat models the composer may switch to; empty until loaded. */
    val models: List<dev.memoh.core.model.ChatModel> = emptyList(),
    val modelCatalogLoading: Boolean = true,
    val modelProviders: List<ModelProvider> = emptyList(),
    /** The model this session uses, from its server-side preference. */
    val selectedModelId: String? = null,
    /** Reasoning effort for [selectedModelId]; null means the model's default. */
    val reasoningEffort: String? = null,
    /** Computers the message may run on; empty when the bot has only one. */
    val workspaceTargets: List<dev.memoh.core.model.WorkspaceTarget> = emptyList(),
    val selectedTargetId: String? = null,
    /** Folders this session may be filed under. */
    val workdirs: List<dev.memoh.core.model.Workdir> = emptyList(),
    val selectedWorkdirId: String? = null,
    /** Agents (runtime hosts) this session may run on. */
    val agents: List<dev.memoh.core.model.BotAgent> = emptyList(),
    val selectedAgentId: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val commandOutput: String? = null,
    val attachmentLoading: Boolean = false,
    val draftRestoring: Boolean = false,
    val busy: Boolean = false,
    val queue: SessionQueueResponse = SessionQueueResponse(),
    val controls: RuntimeControls? = null,
    val goal: RuntimeGoal? = null,
    val controlsError: String? = null,
    val notice: String? = null,
    val sessionInfo: SessionInfo? = null,
    val sessionInfoLoading: Boolean = false,
    val sessionInfoError: String? = null,
    val botPermissions: List<String>? = null,
    val pendingFolderChange: Boolean = false,
    val pendingFolderId: String? = null,
    val navigateSessionId: String? = null,
    val error: String? = null,
    /** True until the first snapshot of the current session arrives. */
    val awaitingSnapshot: Boolean = true,
) {
    val isRunning: Boolean get() = runtime.isRunning || regeneration != null
    val retryableTurnId: String?
        get() = if (!canSend || isRunning || historyLoading || pending.isNotEmpty() ||
            session?.runtimeType !in listOf(null, "model")) null
        else history.lastOrNull { it.isAssistant }?.turnId

    fun canFork(turn: UITurn): Boolean = !busy && !isRunning && session?.isLocal != false &&
        turn.turnId.isNotBlank() && (session?.runtimeType in listOf(null, "model") ||
        session?.runtimeType == "codex" && turn.runtimeForkable == true)
    val modelContextWindow: Long? get() = models.firstOrNull { it.id == selectedModelId }?.config?.contextWindow

    /**
     * The model line shown in the composer: "模型 · 强度".
     *
     * Falls back to the session's preference, then to the first selectable
     * model, so the composer never shows nothing — the user needs to know what
     * will run, and "未选择" answers nothing.
     */
    val modelLabel: String?
        get() {
            val model = models.firstOrNull { it.id == selectedModelId }
                ?: models.firstOrNull()
                ?: return null
            val effort = model.resolveEffort(reasoningEffort)?.takeIf { it in model.efforts }
            return if (effort != null) "${model.label} · ${reasoningEffortLabel(effort)}" else model.label
        }

    /**
     * The capability selectors, in the order the web client shows them.
     *
     * A bound folder stays visible. An unbound folder is only selectable before
     * the first message, after both history and the initial snapshot are known.
     */
    fun capabilities(
        onSelectTarget: (String?) -> Unit,
        onSelectWorkdir: (String?) -> Unit,
        onSelectAgent: (String?) -> Unit,
    ): List<ComposerCapability> = listOfNotNull(
        ComposerCapability(
            label = "云端电脑",
            icon = ComposerIcons.Computer,
            value = workspaceTargets.firstOrNull { it.targetId == selectedTargetId }?.composerName()
                ?: workspaceTargets.firstOrNull { it.primary == true }?.composerName()
                ?: workspaceTargets.firstOrNull()?.composerName()
                ?: "云端电脑",
            options = listOf(CapabilityOption(null, "自动选择")) +
                workspaceTargets.map {
                    CapabilityOption(it.targetId, it.composerName())
                },
            onSelect = onSelectTarget,
            selectedId = selectedTargetId,
            enabled = workspaceTargets.size > 1,
        ),
        if (!selectedWorkdirId.isNullOrBlank() ||
            !historyLoading && historyError == null && !awaitingSnapshot &&
            history.isEmpty() && pending.isEmpty() && liveMessages.isEmpty() && liveUserTurns.isEmpty() && !isRunning) ComposerCapability(
            label = "文件夹",
            icon = ComposerIcons.Folder,
            value = workdirs.firstOrNull { it.id == selectedWorkdirId }?.name
                ?: selectedWorkdirId?.let { "已选文件夹" } ?: "无文件夹",
            options = listOf(CapabilityOption(null, "不放入文件夹")) +
                workdirs.map { CapabilityOption(it.id, it.name.ifBlank { it.id }) },
            onSelect = onSelectWorkdir,
            selectedId = selectedWorkdirId,
            enabled = workdirs.isNotEmpty(),
        ) else null,
        ComposerCapability(
            label = "Agent",
            icon = ComposerIcons.Agent,
            value = agents.firstOrNull { it.id == selectedAgentId }?.name
                ?: if (selectedAgentId == null) "Memoh" else session?.runtimeType?.takeUnless { it == "model" } ?: "已选 Agent",
            options = listOf(CapabilityOption(null, "Memoh")) + agents.map {
                CapabilityOption(it.id, it.name.ifBlank { it.runtime ?: it.id })
            },
            onSelect = onSelectAgent,
            selectedId = selectedAgentId,
            enabled = agents.isNotEmpty(),
        ),
    )

    /**
     * Whether the message can actually be delivered.
     *
     * Requires a live socket: the server assigns run ids over it, so a send with
     * no connection cannot be acknowledged and would leave an optimistic turn
     * stranded.
     */
    val canSend: Boolean
        get() = !busy && !attachmentLoading && !draftRestoring && regeneration == null && !awaitingSnapshot && socketStatus == SocketStatus.Connected &&
            !(session?.isLocal == false)

    /**
     * Whether the composer's *controls* are usable.
     *
     * Deliberately weaker than [canSend]: picking a model, choosing a folder and
     * attaching a file are all local choices that need no connection. Gating them
     * on the socket made the whole composer inert while it reconnected, which
     * reads as the app being broken rather than as the network being down.
     */
    val canCompose: Boolean get() = !busy && session?.isLocal != false

    /** An idle ledger snapshot may have no blocks; it must never erase persisted output. */
    val liveMessages: List<UIMessage>
        get() {
            val run = runtime.run ?: return emptyList()
            if (run.configurationOnly == true) return emptyList()
            if (run.isTerminal && history.any { it.isAssistant && it.turnId == run.turnId }) return emptyList()
            return run.safeMessages
        }

    val liveUserTurns: List<UITurn>
        get() {
            val run = runtime.run?.takeUnless { it.configurationOnly == true } ?: return emptyList()
            val users = run.userTurns?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(run.requestUserTurn ?: run.operation?.replacementUserTurn)
            return users.map { user ->
                UITurn(turnId = user.turnId, turnPosition = user.turnPosition, role = "user", id = user.id,
                    text = user.text, attachments = user.attachments, timestamp = user.timestamp,
                    userMessageKind = user.userMessageKind, skillActivation = user.skillActivation,
                    reply = user.reply, forward = user.forward, senderDisplayName = user.senderDisplayName)
            }.distinctBy { it.listKey }.filterNot { live ->
                history.any { it.isUser && (it.listKey == live.listKey || it.turnId == live.turnId && live.id == null) }
            }
        }

    /**
     * History with the live turn removed: the run view is authoritative for a
     * turn while it is in flight, so showing both would duplicate it.
     */
    val settledHistory: List<UITurn>
        get() {
            val liveTurnId = runtime.run?.turnId ?: return history
            if (liveMessages.isEmpty()) return history
            return history.filterNot { it.isAssistant && it.turnId == liveTurnId }
        }

    /** Keep rendered output through a completion/new-run frame until REST history arrives. */
    internal fun withRuntime(next: RuntimeState, awaitingSnapshot: Boolean = this.awaitingSnapshot): ChatUiState {
        val nextRun = next.run
        val retryAdmitted = regeneration?.let { retry -> nextRun != null &&
            (nextRun.invocationId == retry.invocationId || nextRun.turnId == retry.acceptedTurnId) } == true
        val anchorIndex = nextRun?.operation?.replaceFromMessageId?.takeIf(String::isNotBlank)
            ?.let { anchor -> history.indexOfFirst { it.id == anchor }.takeIf { it >= 0 } }
            ?: if (retryAdmitted) history.indexOfFirst { it.isAssistant && it.turnId == regeneration?.turnId }
                .takeIf { it >= 0 } else null
        val outgoing = when {
            nextRun?.isTerminal == true && nextRun.safeMessages.isNotEmpty() -> nextRun
            nextRun?.isTerminal == true || runtime.run?.turnId != nextRun?.turnId -> runtime.run
            else -> null
        }?.takeIf { it.configurationOnly != true && it.safeMessages.isNotEmpty() &&
            (anchorIndex == null || it.runId == nextRun?.runId) }
        val retained = (anchorIndex?.let(history::take) ?: history).toMutableList()
        if (outgoing != null) {
            val users = outgoing.userTurns?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(outgoing.requestUserTurn ?: outgoing.operation?.replacementUserTurn)
            val insertAt = retained.indexOfFirst { it.isAssistant && it.turnId == outgoing.turnId }
                .takeIf { it >= 0 } ?: retained.size
            val missingUsers = users.map { user ->
                UITurn(turnId = user.turnId, turnPosition = user.turnPosition, role = "user", id = user.id,
                    text = user.text, attachments = user.attachments, timestamp = user.timestamp,
                    userMessageKind = user.userMessageKind, skillActivation = user.skillActivation,
                    reply = user.reply, forward = user.forward, senderDisplayName = user.senderDisplayName)
            }.filterNot { user -> retained.any { it.isUser && (it.listKey == user.listKey || user.id == null && it.turnId == user.turnId) } }
            retained.addAll(insertAt, missingUsers)
            val assistantIndex = retained.indexOfFirst { it.isAssistant && it.turnId == outgoing.turnId }
            if (assistantIndex >= 0) {
                retained[assistantIndex] = retained[assistantIndex].copy(messages = outgoing.safeMessages)
            } else {
                retained += UITurn(turnId = outgoing.turnId, turnPosition = outgoing.turnPosition,
                    role = "assistant", messages = outgoing.safeMessages, timestamp = outgoing.updatedAt.orEmpty())
            }
        }
        val updated = copy(runtime = next, history = retained, awaitingSnapshot = awaitingSnapshot,
            regeneration = regeneration.takeUnless { retryAdmitted })
        val visibleUsers = updated.history.filter { it.isUser } + updated.liveUserTurns
        return updated.copy(pending = pending.filterNot { pending ->
            visibleUsers.any { user -> user.turnId == pending.acceptedTurnId } ||
                next.run?.invocationId == pending.invocationId && visibleUsers.any { it.turnId == next.run?.turnId }
        })
    }
}

/**
 * Chat state for one session.
 *
 * History and the live run are kept separate and merged at read time. That
 * split is what makes reconnection simple: the run view is replaced wholesale by
 * each snapshot, while history is append-only, so neither has to be reconciled
 * against the other.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val json: Json,
    private val socketFactory: ChatSocketFactory,
    private val settings: SettingsStore,
) : ViewModel() {

    suspend fun loadMedia(location: String): ByteArray {
        val generation = repository.generation
        val bytes = (repository.api() ?: error("请先登录")).media(location)
        check(generation == repository.generation) { "账号已切换，请重新打开图片" }
        return bytes
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var socket: ChatSocket? = null
    private var historyJob: kotlinx.coroutines.Job? = null
    private var sessionInfoJob: kotlinx.coroutines.Job? = null
    private var openJob: Job? = null
    private var attachmentJob: Job? = null
    private var draftAddress: Triple<String, String, String>? = null
    private var draftRevision = 0L
    private var attachmentReadId = 0L
    private var draftInvocationId: String? = null

    /** Debounce for coalescing streaming appends into fewer recompositions. */
    private var flushJob: kotlinx.coroutines.Job? = null

    /**
     * Binds to a session and starts streaming.
     *
     * The session object is fetched rather than passed through the navigation
     * route: its channel type and runtime decide whether the composer is usable,
     * and a stale copy in the back stack would show the wrong controls.
     */
    fun open(botId: String, sessionId: String) {
        if (_state.value.session?.id == sessionId && socket != null) return
        openJob?.cancel()
        attachmentJob?.cancel()
        attachmentReadId++
        socket?.close(); socket = null
        val generation = repository.generation
        draftAddress = repository.state.value.account?.accountId?.let { Triple(it, botId, sessionId) }
        draftRevision = 0
        draftInvocationId = null
        _state.value = ChatUiState(botId = botId, historyLoading = true, draftRestoring = true)
        openJob = viewModelScope.launch {
            val address = draftAddress
            val restored = try { address?.let { settings.draft(it.first, it.second, it.third) } }
                catch (e: java.io.IOException) { _state.update { it.copy(error = "读取草稿失败") }; null }
            if (generation != repository.generation) return@launch
            _state.update { it.copy(draft = if (draftRevision == 0L) restored.orEmpty() else it.draft, draftRestoring = false) }
            val api = repository.api()
            val session = runCatching { api?.session(botId, sessionId) }
                .getOrNull()
            currentCoroutineContext().ensureActive()
            if (generation != repository.generation) return@launch
            val resolved = session ?: Session(id = sessionId, botId = botId)
            _state.update {
                it.copy(
                    session = resolved,
                    historyLoading = true,
                    // Seeded from the session's own server-side preference, so
                    // the composer opens showing what will actually be used
                    // rather than an empty default.
                    selectedModelId = resolved.preferredChatModelId,
                    reasoningEffort = resolved.preferredReasoningEffort,
                    selectedWorkdirId = resolved.workdirId,
                    selectedAgentId = resolved.botAgentId,
                )
            }
            connectSocket(botId, resolved)
            loadHistory(botId, resolved)
            loadCapabilities(botId, resolved)
            refreshSessionInfo()
        }
    }

    // -- socket -------------------------------------------------------------

    private fun connectSocket(botId: String, session: Session) {
        socket?.close()
        val endpoint = repository.state.value.endpoint
        val api = repository.api()
        val socket = socketFactory.create(
            botId = botId,
            endpoint = endpoint,
            api = api,
            scope = viewModelScope,
            onEvent = ::onStreamEvent,
            onStatus = { status -> _state.update { it.copy(socketStatus = status) } },
            onResubscribeNeeded = { resubscribe() },
        )
        this.socket = socket
        socket.connect(session.id)
    }

    /**
     * Re-subscribes after a gap. The server never back-fills deltas, so the only
     * correct recovery is to ask for a fresh authoritative snapshot.
     */
    private fun resubscribe() {
        val session = _state.value.session ?: return
        _state.update { it.copy(awaitingSnapshot = true) }
        socket?.subscribe(session.id, cursor = null)
    }

    private fun onStreamEvent(event: UIStreamEvent) {
        val previousRun = _state.value.runtime.run
        when (event.type) {
            UIStreamEvent.RUNTIME_SNAPSHOT -> {
                val snapshot = event.snapshot ?: return
                val sessionId = event.sessionId ?: return
                val epoch = event.epoch ?: return
                val seq = event.seq ?: return
                _state.update { current ->
                    // Ignore frames for a session we are no longer showing.
                    if (sessionId != current.session?.id) return@update current
                    val next = RuntimeReducer.snapshot(
                        state = current.runtime,
                        eventSessionId = sessionId,
                        eventEpoch = epoch,
                        eventSeq = seq,
                        snapshot = snapshot,
                    )
                    current.withRuntime(next, awaitingSnapshot = false)
                }
                if (_state.value.runtime.needsSnapshot) resubscribe()
            }

            UIStreamEvent.RUNTIME_DELTA -> {
                val delta = event.delta ?: return
                val sessionId = event.sessionId ?: return
                val epoch = event.epoch ?: return
                val seq = event.seq ?: return
                _state.update { current ->
                    if (sessionId != current.session?.id) return@update current
                    val next = RuntimeReducer.delta(
                        state = current.runtime,
                        sessionId = sessionId,
                        epoch = epoch,
                        seq = seq,
                        delta = delta,
                    )
                    current.withRuntime(next)
                }
                if (_state.value.runtime.needsSnapshot) resubscribe()
            }

            UIStreamEvent.RUNTIME_DROPPED -> {
                _state.update { it.copy(runtime = it.runtime.copy(needsSnapshot = true)) }
                resubscribe()
            }

            UIStreamEvent.RUN_ACCEPTED -> {
                val invocationId = event.invocationId
                val address = draftAddress
                val acceptedSession = state.value.session
                val acceptedRun = event.runId
                if (acceptedRun != null && address != null && acceptedSession != null &&
                    repository.state.value.account?.accountId == address.first &&
                    (event.sessionId == null || event.sessionId == acceptedSession.id)) {
                    RuntimeMonitorEvents.accepted(LocalRunAccepted(address.first, address.second, acceptedSession, acceptedRun))
                }
                val acceptedDraft = draftInvocationId != null && draftInvocationId == invocationId
                _state.update { current ->
                    // An acceptance can precede the snapshot carrying the user
                    // turn. Keep the bubble until that replacement is visible.
                    current.copy(
                        regeneration = current.regeneration?.let {
                            if (it.invocationId == invocationId) it.copy(acceptedTurnId = event.turnId) else it
                        },
                        pending = current.pending.map {
                            if (it.invocationId == invocationId) it.copy(acceptedTurnId = event.turnId) else it
                        },
                    )
                }
                if (acceptedDraft) { draftInvocationId = null; persistDraft() }
            }

            UIStreamEvent.RUN_REJECTED -> {
                val invocationId = event.invocationId
                _state.update { current ->
                    val rejected = current.pending.firstOrNull { it.invocationId == invocationId }
                    current.copy(
                        pending = current.pending.filterNot { it.invocationId == invocationId },
                        regeneration = current.regeneration?.takeUnless { it.invocationId == invocationId },
                        error = event.message ?: "消息被服务器拒绝",
                        // Restore the text so it is not lost; the composer picks
                        // this up as a draft.
                        draft = listOfNotNull(rejected?.text, current.draft.takeIf(String::isNotBlank)).joinToString("\n"),
                        attachments = rejected?.attachments.orEmpty() + current.attachments,
                    )
                }
                persistDraft()
                if (draftInvocationId == invocationId) draftInvocationId = null
            }

            UIStreamEvent.CONTROL_ACK -> {
                if (!event.code.isNullOrBlank()) _state.update { it.copy(error = "操作未完成：${event.code}") }
                else if (event.applied == true) _state.update { it.copy(notice = "操作已提交") }
            }

            UIStreamEvent.ERROR -> {
                // An in-stream error must not clear what is already rendered.
                val message = event.message ?: "运行出错"
                _state.update { it.copy(error = message,
                    regeneration = it.regeneration?.takeUnless { retry -> retry.invocationId == event.invocationId }) }
            }

            UIStreamEvent.SESSION_CREATED -> {
                // A session-less send created one; the shell adopts it on next
                // refresh rather than rewriting the current screen's identity.
            }

            else -> Unit
        }
        if (!state.value.runtime.needsSnapshot && draftInvocationId != null && state.value.runtime.run?.invocationId == draftInvocationId) {
            draftInvocationId = null
            persistDraft()
        }
        if (event.type in setOf(UIStreamEvent.RUNTIME_SNAPSHOT, UIStreamEvent.RUNTIME_DELTA) &&
            previousRun != null && !previousRun.isTerminal && previousRun.configurationOnly != true) {
            val current = _state.value
            if (current.runtime.run == null || current.runtime.run?.isTerminal == true) {
                current.session?.let { session -> viewModelScope.launch { loadHistory(current.botId, session) } }
                refreshSessionInfo()
            }
        }
    }

    // -- history ------------------------------------------------------------

    /**
     * Loads what the composer can choose from: models, computers, folders.
     *
     * These are optional to the conversation. Fetch them independently so a
     * slow model catalogue cannot delay the computer, folder or Agent controls.
     * A failed request leaves only its own control unavailable.
     */
    private fun loadCapabilities(botId: String, session: Session) {
        viewModelScope.launch {
            val api = repository.api() ?: return@launch

            launch {
                try {
                    val permissions = api.bot(botId).currentUserPermissions
                    _state.update { if (it.botId == botId && it.session?.id == session.id) it.copy(botPermissions = permissions) else it }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Older servers may omit permission metadata. */ }
            }

            launch {
                try {
                    if (session.isAcpRuntime) {
                        applyAcp(api.acpRuntime(botId, session.id))
                    } else {
                        val agentId = session.botAgentId
                        val catalog = if (session.runtimeType in listOf("codex", "claude-code") && agentId != null)
                            api.agentModels(botId, agentId) else null
                        val models = (catalog?.pickerModels ?: api.models()).filter { it.isSelectable }
                        _state.update { current ->
                            if (current.session?.id != session.id) current else {
                                val preferred = current.selectedModelId?.takeIf(String::isNotBlank)
                                    ?: catalog?.configuredModelId?.takeIf(String::isNotBlank)
                                    ?: catalog?.models?.firstOrNull { it.default }?.id ?: models.firstOrNull()?.id
                                val selected = models.firstOrNull { it.id == preferred || it.modelId == preferred }
                                current.copy(models = models, selectedModelId = selected?.id ?: preferred,
                                    reasoningEffort = current.reasoningEffort?.takeIf(String::isNotBlank)
                                        ?: catalog?.configuredReasoningEffort?.takeIf(String::isNotBlank))
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _state.update { if (it.session?.id == session.id) it.copy(error = "模型列表加载失败：${e.message}") else it } }
                finally { _state.update { if (it.session?.id == session.id) it.copy(modelCatalogLoading = false) else it } }
                refreshSessionInfo()
            }
            if (!session.isAcpRuntime && session.runtimeType !in listOf("codex", "claude-code")) launch {
                val providers = try { api.providers() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                _state.update { if (it.session?.id == session.id) it.copy(modelProviders = providers) else it }
            }
            launch {
                val targets = runCatching { api.workspaceTargets(botId).targets }
                    .getOrDefault(emptyList())
                _state.update { current ->
                    if (current.botId != botId || current.session?.id != session.id) current
                    else current.copy(workspaceTargets = targets)
                }
            }
            launch {
                val workdirs = runCatching { api.workdirs(botId) }
                    .getOrDefault(emptyList()).filter { it.isActive }
                _state.update { current ->
                    if (current.botId != botId || current.session?.id != session.id) current
                    else current.copy(workdirs = workdirs)
                }
            }
            launch {
                val agents = runCatching { api.agents(botId) }
                    .getOrDefault(emptyList()).filter { it.enabled != false }
                _state.update { current ->
                    if (current.botId != botId || current.session?.id != session.id) current
                    else current.copy(agents = agents)
                }
            }
        }
    }

    private fun applyAcp(runtime: AcpRuntimeStatus) {
        val efforts = runtime.reasoning?.availableEfforts.orEmpty().mapNotNull { it.id }
        val models = runtime.models?.availableModels.orEmpty().mapNotNull { model ->
            model.id?.let { ChatModel(id = it, type = "chat", displayName = model.name,
                capabilities = ModelCapabilities(availableEfforts = efforts)) }
        }
        _state.update { current ->
            if (runtime.sessionId != null && runtime.sessionId != current.session?.id) current
            else current.copy(models = models, selectedModelId = runtime.models?.currentModelId,
                reasoningEffort = runtime.reasoning?.currentEffort)
        }
    }

    private fun operation(work: suspend (MemohApi, ChatUiState, Session) -> Unit) {
        val current = state.value
        val session = current.session ?: return
        if (current.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try { work(repository.api() ?: error("未登录"), current, session) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "操作失败") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun selectModel(modelId: String) {
        val current = state.value
        val model = current.models.firstOrNull { it.id == modelId } ?: return
        savePreference(modelId, model.resolveEffort(current.reasoningEffort))
    }
    fun selectReasoningEffort(effort: String?) = savePreference(state.value.selectedModelId ?: state.value.models.firstOrNull()?.id, effort)
    private fun savePreference(modelId: String?, effort: String?) = operation { api, current, session ->
        if (session.isAcpRuntime) {
            val runtime = if (modelId != current.selectedModelId && modelId != null) api.setAcpModel(current.botId, session.id, modelId)
                else api.setAcpReasoning(current.botId, session.id, effort)
            applyAcp(runtime)
        } else {
            val updated = api.updateSession(current.botId, session.id, apiBody("preferred_chat_model_id" to modelId,
                "preferred_reasoning_effort" to (effort ?: ""), "expected_model_preference_revision" to (session.modelPreferenceRevision ?: "")))
            _state.update { it.copy(session = updated, selectedModelId = updated.preferredChatModelId ?: modelId,
                reasoningEffort = updated.preferredReasoningEffort ?: effort) }
        }
        refreshSessionInfo()
    }
    fun selectWorkspaceTarget(targetId: String?) = _state.update { it.copy(selectedTargetId = targetId) }
    fun selectWorkdir(workdirId: String?) {
        if (workdirId == state.value.selectedWorkdirId) return
        _state.update { it.copy(pendingFolderChange = true, pendingFolderId = workdirId) }
    }
    fun cancelFolderChange() { _state.update { it.copy(pendingFolderChange = false) } }
    fun confirmFolderChange() = operation { api, current, session ->
        val created = api.newSession(current.botId, apiBody("workdir_id" to current.pendingFolderId,
            "bot_agent_id" to session.botAgentId, "type" to "chat"))
        _state.update { it.copy(pendingFolderChange = false, navigateSessionId = created.id) }
    }
    fun clearNavigation() { _state.update { it.copy(navigateSessionId = null) } }
    fun newSession() = operation { api, current, session ->
        val created = api.newSession(current.botId, apiBody("type" to "chat", "workdir_id" to session.workdirId,
            "bot_agent_id" to session.botAgentId, "preferred_chat_model_id" to current.selectedModelId,
            "preferred_reasoning_effort" to current.reasoningEffort))
        _state.update { it.copy(navigateSessionId = created.id) }
    }
    fun selectAgent(agentId: String?) = operation { api, current, session ->
        check(!current.isRunning) { "请等当前任务结束后切换 Agent" }
        val updated = api.updateSession(current.botId, session.id, apiBody("bot_agent_id" to (agentId ?: ""),
            "runtime_type" to if (agentId == null) "model" else null))
        _state.update { it.copy(session = updated, selectedAgentId = updated.botAgentId,
            selectedModelId = updated.preferredChatModelId, models = emptyList(), modelCatalogLoading = true, modelProviders = emptyList(), reasoningEffort = updated.preferredReasoningEffort, awaitingSnapshot = true) }
        connectSocket(current.botId, updated)
        loadCapabilities(current.botId, updated)
    }

    fun refreshControls() {
        val current = state.value
        val session = current.session ?: return
        viewModelScope.launch {
            val api = repository.api() ?: return@launch
            try { val queue = api.queue(current.botId, session.id); _state.update { it.copy(queue = queue) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(controlsError = "队列：${e.message}") } }
            try {
                val controls = api.runtimeControls(current.botId, session.id)
                val goal = if (controls.capabilities?.goal == true) api.runtimeGoal(current.botId, session.id)["goal"]?.takeIf { it !is JsonNull }?.let { json.decodeFromJsonElement(RuntimeGoal.serializer(), it) } else null
                _state.update { it.copy(controls = controls, goal = goal, controlsError = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(controlsError = "运行控制：${e.message}") } }
        }
    }
    fun enqueue(text: String, steer: Boolean) = operation { api, current, session ->
        require(text.isNotBlank()) { "请输入内容" }
        api.enqueue(current.botId, session.id, text, steer, UUID.randomUUID().toString())
        _state.update { it.copy(draft = "", notice = "已加入${if (steer) "插话" else "后续"}队列") }; refreshControls()
    }
    fun editQueue(item: QueueItem, text: String, steer: Boolean) = operation { api, current, session ->
        api.editQueueItem(current.botId, session.id, item.itemId, text, steer); refreshControls()
    }
    fun deleteQueue(item: QueueItem, steer: Boolean) = operation { api, current, session ->
        api.removeQueueItem(current.botId, session.id, item.itemId, steer); refreshControls()
    }
    fun moveQueueUp(item: QueueItem, before: QueueItem, steer: Boolean) = operation { api, current, session ->
        api.reorderQueue(current.botId, session.id, item.itemId, before.itemId, steer); refreshControls()
    }
    fun promoteQueue(item: QueueItem) = operation { api, current, session ->
        api.promoteQueueItem(current.botId, session.id, item.itemId); refreshControls()
    }
    fun setMode(id: String, kind: String) = operation { api, current, session ->
        api.setRuntimeMode(current.botId, session.id, id, kind); refreshControls()
    }
    fun goalAction(action: String) = operation { api, current, session ->
        api.controlRuntimeGoal(current.botId, session.id, action); refreshControls()
    }
    fun command(value: String) = operation { api, current, session ->
        val result = api.runtimeCommand(current.botId, session.id, apiBody("command" to value))
        val message = (result as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        _state.update { it.copy(commandOutput = message, notice = "命令已执行") }; refreshControls()
    }
    fun compact() = operation { api, current, session ->
        api.compactSession(current.botId, session.id)
        _state.update { it.copy(notice = "会话已压缩") }; loadHistory(current.botId, session, replace = true); refreshControls()
        refreshSessionInfo()
    }
    fun fork(turnId: String) = operation { api, current, session ->
        val forked = api.forkSession(current.botId, session.id, turnId)
        _state.update { it.copy(navigateSessionId = forked.id) }
    }
    fun quickAction(id: String) = operation { api, current, session ->
        val result = api.quickAction(current.botId, apiBody("action_id" to id, "session_id" to session.id,
            "invocation_id" to UUID.randomUUID().toString())) as? JsonObject
        val failure = result?.get("error")?.takeIf { it !is JsonNull }
        check(failure == null) { "快捷操作失败：$failure" }
        val payload = result?.get("result")?.takeIf { it !is JsonNull }?.let { json.decodeFromJsonElement(CommandActionResult.serializer(), it) }
        val output = listOfNotNull(payload?.title, payload?.text,
            payload?.items?.joinToString("\n") { listOfNotNull(it.title, it.description).joinToString(" · ") }).joinToString("\n")
        _state.update { it.copy(commandOutput = output.takeIf(String::isNotBlank), notice = "快捷操作已完成") }
    }

    fun refreshSessionInfo() {
        sessionInfoJob?.cancel()
        val current = state.value
        val session = current.session ?: return
        val generation = repository.generation
        sessionInfoJob = viewModelScope.launch {
            _state.update { it.copy(sessionInfoLoading = true, sessionInfoError = null) }
            try {
                val info = (repository.api() ?: error("请先登录")).sessionInfo(current.botId, session.id, current.selectedModelId)
                _state.update {
                    if (repository.generation != generation || it.botId != current.botId || it.session?.id != session.id) it
                    else it.copy(sessionInfo = info, sessionInfoLoading = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { if (it.session?.id == session.id) it.copy(sessionInfoLoading = false, sessionInfoError = e.message ?: "上下文信息加载失败") else it }
            }
        }
    }

    private fun loadHistory(botId: String, session: Session, before: String? = null, replace: Boolean = false) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            val api = repository.api()
            if (api == null) {
                _state.update { it.copy(historyLoading = false, historyError = "未登录") }
                return@launch
            }
            if (before == null) _state.update { it.copy(historyLoading = true, historyError = null) }
            else _state.update { it.copy(loadingMore = true, historyError = null) }

            runCatching { api.messages(botId, session.id, limit = PAGE_SIZE, beforeMessageId = before) }
                .fold(
                    onSuccess = { page ->
                        _state.update { current ->
                            if (current.botId != botId || current.session?.id != session.id) return@update current
                            val merged = if (before == null) {
                                if (replace) page.items else mergeLatestHistory(current.history, page.items)
                            } else {
                                // Older page goes in front; keep chronological order.
                                page.items + current.history
                            }
                            current.copy(
                                history = merged.distinctBy { it.listKey },
                                historyLoading = false,
                                historyError = null,
                                loadingMore = false,
                                hasMoreHistory = page.items.any { pageTurn ->
                                    before == null || current.history.none { it.listKey == pageTurn.listKey }
                                } && page.items.any { !it.id.isNullOrBlank() },
                            )
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        _state.update {
                            if (it.botId != botId || it.session?.id != session.id) return@update it
                            it.copy(
                                historyLoading = false,
                                loadingMore = false,
                                historyError = if (error is kotlinx.serialization.SerializationException)
                                    "历史消息格式不兼容，请重试或更新应用。"
                                else "历史消息加载失败：${error.message ?: "请重试"}",
                            )
                        }
                    },
                )
        }
    }

    /** Loads the page above the oldest turn currently held. */
    fun loadOlder() {
        val current = _state.value
        if (current.loadingMore || !current.hasMoreHistory || current.historyLoading) return
        val oldest = current.history.firstNotNullOfOrNull { it.id?.takeIf(String::isNotBlank) } ?: return
        loadHistory(current.botId, current.session ?: return, before = oldest)
    }

    fun retryHistory() {
        val current = state.value
        loadHistory(current.botId, current.session ?: return)
    }

    // -- sending ------------------------------------------------------------

    /** Replaces the latest assistant reply through the server's retry admission protocol. */
    fun regenerate(turnId: String) {
        val current = _state.value
        if (current.retryableTurnId != turnId) return
        val session = current.session ?: return
        val activeSocket = socket ?: return
        val invocationId = UUID.randomUUID().toString()
        // Keep the original reply until the server admits the replacement.
        // A rejection then leaves both history and the user's draft intact.
        historyJob?.cancel()
        _state.update { it.copy(regeneration = PendingRegeneration(invocationId, turnId), loadingMore = false, error = null) }
        activeSocket.send(WSClientMessage(type = WSClientMessage.RETRY_MESSAGE,
            invocationId = invocationId, sessionId = session.id, turnId = turnId,
            modelId = current.selectedModelId ?: current.models.firstOrNull()?.id,
            reasoningEffort = (current.models.firstOrNull { it.id == current.selectedModelId }
                ?: current.models.firstOrNull())?.resolveEffort(current.reasoningEffort),
            workspaceTargetId = current.selectedTargetId))
    }

    fun send(text: String) {
        val current = _state.value
        val session = current.session ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && current.attachments.isEmpty()) return
        if (current.attachmentLoading || current.draftRestoring) {
            _state.update { it.copy(error = "请等待草稿和附件读取完成") }; return
        }
        if (!current.canSend || current.busy) {
            setDraft(text)
            _state.update { it.copy(error = "会话正在连接，请稍后发送") }; return
        }
        if (!session.isLocal) {
            _state.update { it.copy(error = "此会话来自外部渠道，只能查看") }
            return
        }

        val invocationId = UUID.randomUUID().toString()
        draftInvocationId = invocationId
        draftAddress?.let { settings.saveDraft(it.first, it.second, it.third, text) }
        _state.update {
            it.copy(
                pending = it.pending + PendingTurn(invocationId, trimmed, current.attachments),
                draft = "",
                attachments = emptyList(),
                error = null,
            )
        }

        socket?.send(
            WSClientMessage(
                type = WSClientMessage.MESSAGE,
                invocationId = invocationId,
                sessionId = session.id,
                text = trimmed,
                attachments = current.attachments.takeIf { it.isNotEmpty() },
                modelId = if (session.isAcpRuntime) null else current.selectedModelId ?: current.models.firstOrNull()?.id,
                reasoningEffort = if (session.isAcpRuntime) null else
                    (current.models.firstOrNull { it.id == current.selectedModelId } ?: current.models.firstOrNull())?.resolveEffort(current.reasoningEffort),
                workspaceTargetId = current.selectedTargetId,
            ),
        )
    }

    fun abort() {
        val run = _state.value.runtime.run ?: return
        val session = _state.value.session ?: return
        socket?.abort(runId = run.runId, sessionId = session.id)
    }

    /** Approves or rejects a pending tool decision. */
    fun respondToApproval(
        runId: String,
        approvalId: String,
        approve: Boolean,
        optionId: String? = null,
        reason: String? = null,
    ) {
        val session = _state.value.session ?: return
        socket?.respondToApproval(
            runId = runId,
            sessionId = session.id,
            decisionId = approvalId,
            decision = if (approve) "approve" else "reject",
            optionId = optionId,
            reason = reason,
        )
    }

    fun respondToUserInput(
        runId: String,
        userInputId: String,
        answers: List<WSUserInputAnswer>?,
        canceled: Boolean = false,
    ) {
        val session = _state.value.session ?: return
        socket?.respondToUserInput(
            runId = runId,
            sessionId = session.id,
            decisionId = userInputId,
            answers = answers,
            canceled = canceled,
            reason = if (canceled) "user_canceled" else null,
        )
    }

    fun clearError() = _state.update { it.copy(error = null, notice = null) }
    fun removeAttachment(index: Int) { _state.update { it.copy(attachments = it.attachments.filterIndexed { i, _ -> i != index }) } }
    fun attach(context: Context, uri: Uri) = attach(context, listOf(uri))

    fun cancelAttachments() { attachmentReadId++; attachmentJob?.cancel(); _state.update { it.copy(attachmentLoading = false) } }

    fun attach(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (state.value.attachmentLoading) { _state.update { it.copy(error = "附件正在读取，请稍后再添加") }; return }
        val app = context.applicationContext
        val generation = repository.generation
        val address = draftAddress
        val readId = ++attachmentReadId
        _state.update { it.copy(attachmentLoading = true) }
        attachmentJob = viewModelScope.launch {
            try { for (uri in uris) {
                try {
                check(_state.value.attachments.size < 8) { "一次最多添加 8 个附件" }
                val attachment = withContext(Dispatchers.IO) {
                    val resolver = app.contentResolver
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "附件"
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 10 * 1024 * 1024) { "请使用 10 MB 以内的附件" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("无法读取附件")
                    check(bytes.isNotEmpty()) { "$name 是空文件" }
                    ChatAttachment(type = if (mime.startsWith("image/")) "image" else "file", mime = mime, name = name,
                        base64 = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
                if (generation != repository.generation || address != draftAddress || readId != attachmentReadId) return@launch
                val total = _state.value.attachments.sumOf { attachmentSize(it) } + attachmentSize(attachment)
                check(total <= 20 * 1024 * 1024) { "附件总大小不能超过 20 MB" }
                _state.update { it.copy(attachments = it.attachments + attachment) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _state.update { it.copy(error = e.message ?: "读取附件失败") } }
            } } finally {
                if (address == draftAddress && readId == attachmentReadId) _state.update { it.copy(attachmentLoading = false) }
            }
        }
    }

    fun importShared(context: Context, text: String, uris: List<Uri>) {
        setDraft(listOf(state.value.draft, text).filter(String::isNotBlank).joinToString("\n"))
        attach(context, uris)
    }

    fun setDraft(text: String) { draftRevision++; _state.update { it.copy(draft = text) }; persistDraft() }

    private fun persistDraft() {
        draftAddress?.let { settings.saveDraft(it.first, it.second, it.third, state.value.draft) }
    }

    override fun onCleared() {
        super.onCleared()
        flushJob?.cancel()
        socket?.close()
        socket = null
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

internal fun attachmentSize(attachment: ChatAttachment): Long {
    val payload = attachment.base64.substringAfter(',', "")
    return payload.length.toLong() * 3 / 4 - payload.takeLast(2).count { it == '=' }
}
