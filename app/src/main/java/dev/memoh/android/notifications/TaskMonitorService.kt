package dev.memoh.android.notifications

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.RuntimeMonitorEvents
import dev.memoh.core.model.*
import dev.memoh.core.network.*
import dev.memoh.feature.chat.ChatSocketFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@AndroidEntryPoint
class TaskMonitorService : Service() {
    @Inject lateinit var repository: SessionRepository
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var socketFactory: ChatSocketFactory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: TaskNotifications
    private var started = false
    private val unpromotedRuns = mutableSetOf<String>()
    private val redraws = Channel<Unit>(Channel.CONFLATED)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); notifications = TaskNotifications(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == UNPIN) {
            intent.getStringExtra("run_id")?.let { unpromotedRuns.add(it); redraws.trySend(Unit) }
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            scope.launch { settings.setBackgroundMonitor(false); stopSelf() }
            return START_NOT_STICKY
        }
        if (!notifications.allowed || !repository.state.value.loggedIn) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, TaskNotifications.FOREGROUND_ID, notifications.monitor("正在连接"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (!started) {
            started = true
            scope.launch {
                combine(repository.state, settings.backgroundMonitor, settings.lastBotId) { session, enabled, bot ->
                    Triple(session, enabled, bot)
                }.distinctUntilChanged().collectLatest { (session, enabled, bot) ->
                    notifications.clear()
                    unpromotedRuns.clear()
                    if (!session.loggedIn || !enabled) { stopSelf(); return@collectLatest }
                    if (bot.isNullOrBlank()) { status("尚未选择 Bot"); return@collectLatest }
                    monitor(session.account?.accountId ?: return@collectLatest, bot)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun status(text: String) {
        if (MonitorStatus.state.value == MonitorState(true, text)) return
        MonitorStatus.update(true, text)
        if (notifications.allowed) getSystemService(android.app.NotificationManager::class.java)
            .notify(TaskNotifications.FOREGROUND_ID, notifications.monitor(text))
    }

    private suspend fun monitor(account: String, bot: String) = coroutineScope {
        val generation = repository.generation
        val api = repository.api() ?: return@coroutineScope
        val tracker = TaskTracker()
        val sessions = mutableMapOf<String, Session>()
        val refreshes = Channel<Unit>(Channel.CONFLATED)
        var connected = false
        var discoveryConnected = false
        var indexed = false
        var replyNotifications = settings.notifyOnReplyDone.first()
        var decisionNotifications = settings.notifyOnDecisions.first()
        var live = settings.liveUpdates.first()
        var socket: ChatSocket? = null
        val admittedSessions = mutableSetOf<String>()
        fun current() = generation == repository.generation && repository.state.value.loggedIn
        fun report() {
            val line = when {
                !connected -> "连接中断或正在连接，等待重连"
                !indexed -> "正在加载会话"
                !discoveryConnected -> "任务连接正常；新会话监听正在重连"
                else -> "正在监控当前 Bot 的 ${sessions.size} 个会话"
            }
            status(line)
        }
        fun post(sessionId: String, task: TrackedTask?, alert: Boolean = false) {
            if (task == null || task.terminal && !replyNotifications) notifications.cancelTask(account, bot, sessionId)
            else notifications.task(account, bot, sessionId, sessions[sessionId]?.title?.takeIf(String::isNotBlank) ?: "未命名任务",
                task, alert && (if (task.terminal) replyNotifications else decisionNotifications), live && task.runId !in unpromotedRuns)
        }
        socket = socketFactory.create(bot, repository.state.value.endpoint, api, this,
            onEvent = { event -> launch {
                if (!current()) return@launch
                if (!notifications.allowed) { settings.setBackgroundMonitor(false); return@launch }
                val id = event.sessionId ?: return@launch
                if (id !in sessions) return@launch
                val update = tracker.apply(event)
                if (tracker.states[id]?.needsSnapshot == true)
                    socket?.send(WSClientMessage(type = WSClientMessage.RUNTIME_SUBSCRIBE, sessionId = id))
                update?.let { post(id, it.task, it.alert) }
            } }, onStatus = { socketStatus -> launch {
                if (!current()) return@launch
                connected = socketStatus == SocketStatus.Connected
                if (!connected) {
                    tracker.disconnected()
                    tracker.states.forEach { (id, state) -> state.run?.takeUnless { it.isTerminal }?.let {
                        post(id, TrackedTask(it.runId, "连接中断，等待恢复", false, it.startedAt))
                    } }
                }
                report()
            } }, onResubscribeNeeded = { /* Each dropped session is handled by its own reducer above. */ })
        try {
            launch {
                RuntimeMonitorEvents.accepted.collect { event ->
                    if (event.accountId == account && event.botId == bot && current()) {
                        tracker.accepted(event.runId)
                        admittedSessions.add(event.session.id)
                        sessions.putIfAbsent(event.session.id, event.session)
                        socket?.observeSessions(sessions.keys.toSet())
                        // This also covers a completion arriving before sidebar discovery.
                        socket?.send(WSClientMessage(type = WSClientMessage.RUNTIME_SUBSCRIBE, sessionId = event.session.id))
                    }
                }
            }
            launch { for (ignored in redraws) tracker.activeTasks().forEach { (id, task) -> post(id, task) } }
            launch { settings.notifyOnReplyDone.distinctUntilChanged().collect { replyNotifications = it } }
            launch { settings.notifyOnDecisions.distinctUntilChanged().collect { decisionNotifications = it } }
            launch { settings.liveUpdates.distinctUntilChanged().collect { enabled ->
                live = enabled
                tracker.activeTasks().forEach { (id, task) -> post(id, task) }
            } }
            launch {
                for (ignored in refreshes) {
                    while (current()) {
                        try {
                            val fetched = mutableMapOf<String, Session>()
                            var cursor: String? = null
                            do {
                                val page = api.sessions(bot, types = Session.CHAT_TYPES, limit = 50, cursor = cursor)
                                page.items.forEach { fetched[it.id] = it }
                                val next = page.nextCursor?.takeIf(String::isNotBlank)
                                if (next == cursor) break
                                cursor = next
                            } while (cursor != null)
                            if (!current()) return@launch
                            admittedSessions.removeAll(fetched.keys)
                            admittedSessions.forEach { id -> sessions[id]?.let { fetched[id] = it } }
                            (sessions.keys - fetched.keys).forEach { notifications.cancelTask(account, bot, it); tracker.states.remove(it) }
                            sessions.clear(); sessions.putAll(fetched)
                            indexed = true
                            socket?.observeSessions(sessions.keys.toSet())
                            report(); break
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { status("会话列表加载失败，正在重试"); delay(5000) }
                    }
                }
            }
            launch {
                while (current()) {
                    try {
                        api.sessionActivity(bot).collect { event ->
                            if (!current()) return@collect
                            discoveryConnected = true
                            if (!notifications.allowed) { settings.setBackgroundMonitor(false); return@collect }
                            when (event.type) {
                                SessionActivityEvent.SESSION_CREATED,
                                SessionActivityEvent.DROPPED, SessionActivityEvent.ACTIVITY_READY -> refreshes.trySend(Unit)
                                SessionActivityEvent.SESSION_TOUCHED -> if (event.sessionId !in sessions) refreshes.trySend(Unit)
                                SessionActivityEvent.SESSION_TITLE_CHANGED -> event.sessionId?.let { id ->
                                    sessions[id]?.let { sessions[id] = it.copy(title = event.title ?: it.title) }
                                    tracker.activeTasks()[id]?.let { post(id, it) }
                                }
                            }
                            report()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { discoveryConnected = false; report() }
                    delay(5000)
                }
            }
            refreshes.trySend(Unit)
            socket.connect()
            awaitCancellation()
        } finally { socket.close(); refreshes.close(); notifications.clear() }
    }

    override fun onDestroy() {
        scope.cancel(); notifications.clear()
        MonitorStatus.update(false, "后台监控已停止")
        super.onDestroy()
    }

    companion object {
        const val STOP = "dev.memoh.android.STOP_MONITOR"
        const val UNPIN = "dev.memoh.android.UNPIN_TASK"
    }
}
