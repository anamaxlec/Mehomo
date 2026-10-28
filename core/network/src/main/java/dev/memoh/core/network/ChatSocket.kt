package dev.memoh.core.network

import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.UIStreamEvent
import dev.memoh.core.model.WSClientMessage
import dev.memoh.core.model.acknowledgedRequestKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.utf8Size
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Connection lifecycle, surfaced to the UI as a status indicator. */
enum class SocketStatus { Idle, Connecting, Connected, Reconnecting, Closed }
enum class SocketSendResult { Queued, TooLarge, Closed }

/**
 * The bot-level chat socket.
 *
 * One connection per bot (not per session) — the server multiplexes sessions
 * over it via `runtime_subscribe`, so switching conversations does not tear
 * down the transport and steer/follow-up frames keep flowing.
 *
 * Two guarantees the rest of the app relies on:
 *
 *  1. **Reliable delivery.** Frames that open a turn or answer a decision are
 *     kept in a pending table keyed by invocation/control id. On reconnect the
 *     unacknowledged ones are re-sent; the server deduplicates by that same id.
 *     An acknowledged frame is never re-sent, because once bytes reach the
 *     socket the delivery outcome is unknown and replaying could duplicate work.
 *
 *  2. **Resubscribe before use.** After every (re)connect the session is
 *     re-subscribed and the composer stays disabled until the authoritative
 *     snapshot arrives, so a send can never race a stale view.
 */
class ChatSocket(
    private val client: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val buildRequest: suspend () -> Request,
    private val onEvent: (UIStreamEvent) -> Unit,
    private val onStatus: (SocketStatus) -> Unit = {},
    /** Called when a re-subscribe is required (gap detected or dropped). */
    private val onResubscribeNeeded: () -> Unit = {},
) {
    private val mutex = Mutex()
    @Volatile private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var status: SocketStatus = SocketStatus.Idle

    /** Frames awaiting an ack, keyed by [WSClientMessage.reliableKey]. */
    private class PendingFrame(val payload: String, var sentOn: WebSocket? = null)
    private val pending = ConcurrentHashMap<String, PendingFrame>()

    /** Fire-and-forget frames queued while offline, flushed on connect. */
    private val sendQueue = Channel<String>(Channel.UNLIMITED)

    private val reconnectDelayMs = AtomicLong(INITIAL_RECONNECT_MS)
    private val controlCounter = AtomicLong(0)

    /** Session currently subscribed, re-subscribed automatically after reconnect. */
    private var subscribedSessionId: String? = null

    /** Read-only sidebar subscriptions; the server permits many sessions on one socket. */
    private var observedSessionIds: Set<String> = emptySet()

    /** Last known cursor, sent with a re-subscribe so the server can resume. */
    private var cursor: dev.memoh.core.model.RuntimeCursor? = null

    @Volatile private var closed = false

    // -- lifecycle ----------------------------------------------------------

    fun connect(sessionId: String? = null) {
        scope.launch {
            mutex.withLock {
                if (closed) return@withLock
                sessionId?.let { subscribedSessionId = it }
                openLocked()
            }
        }
    }

    /** Resume a failed transport immediately, without replacing a healthy connection or handshake. */
    fun reconnectIfNeeded() {
        scope.launch {
            mutex.withLock {
                if (closed || socket != null) return@withLock
                reconnectJob?.cancel()
                reconnectJob = null
                openLocked()
            }
        }
    }

    private suspend fun openLocked() {
        setStatus(if (reconnectDelayMs.get() > INITIAL_RECONNECT_MS) SocketStatus.Reconnecting else SocketStatus.Connecting)
        try {
            val request = buildRequest()
            if (!closed) socket = client.newWebSocket(request, Listener())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            setStatus(SocketStatus.Closed)
        } catch (e: Exception) {
            setStatus(SocketStatus.Reconnecting)
            scheduleReconnectLocked()
        }
    }

    fun close() {
        closed = true
        reconnectJob?.cancel()
        // onCleared cancels the owning scope. Cleanup must finish without
        // launching into that scope, or the connection survives the screen.
        socket?.cancel()
        socket = null
        pending.clear()
        subscribedSessionId = null
        observedSessionIds = emptySet()
        setStatus(SocketStatus.Closed)
    }

    /**
     * Subscribes to a session's runtime state, replacing any previous
     * subscription. The UI must wait for the resulting snapshot before enabling
     * the composer.
     */
    fun subscribe(sessionId: String, cursor: dev.memoh.core.model.RuntimeCursor? = null) {
        subscribedSessionId = sessionId
        this.cursor = cursor
        send(WSClientMessage(
            type = WSClientMessage.RUNTIME_SUBSCRIBE,
            sessionId = sessionId,
            cursor = cursor,
        ))
    }

    fun unsubscribe(sessionId: String) {
        if (subscribedSessionId == sessionId) subscribedSessionId = null
        send(WSClientMessage(type = WSClientMessage.RUNTIME_UNSUBSCRIBE, sessionId = sessionId))
    }

    /** Replaces the sidebar's subscriptions without sending messages or starting runs. */
    fun observeSessions(sessionIds: Set<String>) {
        scope.launch {
            mutex.withLock {
                if (closed) return@withLock
                val next = sessionIds.filter(String::isNotBlank).toSet()
                val previous = observedSessionIds
                observedSessionIds = next
                val current = socket?.takeIf { status == SocketStatus.Connected } ?: return@withLock
                (previous - next).filter { it != subscribedSessionId }.forEach { id ->
                    current.send(json.encodeToString(WSClientMessage.serializer(),
                        WSClientMessage(type = WSClientMessage.RUNTIME_UNSUBSCRIBE, sessionId = id)))
                }
                (next - previous).filter { it != subscribedSessionId }.forEach { id ->
                    current.send(json.encodeToString(WSClientMessage.serializer(),
                        WSClientMessage(type = WSClientMessage.RUNTIME_SUBSCRIBE, sessionId = id)))
                }
            }
        }
    }

    // -- sending ------------------------------------------------------------

    /** Reject frames that cannot fit in OkHttp's WebSocket queue before recording them. */
    fun send(message: WSClientMessage): SocketSendResult {
        if (closed) return SocketSendResult.Closed
        val payload = json.encodeToString(WSClientMessage.serializer(), message)
        if (payload.utf8Size() > MAX_MESSAGE_BYTES) return SocketSendResult.TooLarge
        val key = message.reliableKey
        val frame = key?.let {
            // Remember before sending: the ack can arrive before this returns.
            PendingFrame(payload).also { frame -> pending[it] = frame }
        }
        scope.launch { deliver(payload, key, frame) }
        return SocketSendResult.Queued
    }

    private suspend fun deliver(payload: String, key: String?, frame: PendingFrame?) {
        mutex.withLock {
            val current = socket
            if (current != null && status == SocketStatus.Connected) {
                if (key == null) current.send(payload)
                else if (frame != null && pending[key] === frame) sendPending(current, frame)
            } else if (key == null) {
                // Reliable frames already live in `pending` for the next connect.
                sendQueue.send(payload)
            }
        }
    }

    /** Called under [mutex] so an onOpen replay and a queued delivery cannot race. */
    private fun sendPending(webSocket: WebSocket, frame: PendingFrame) {
        if (frame.sentOn !== webSocket && webSocket.send(frame.payload)) frame.sentOn = webSocket
    }

    /** Aborts the run currently occupying a session. */
    fun abort(runId: String, sessionId: String) {
        send(WSClientMessage(
            type = WSClientMessage.ABORT,
            runId = runId,
            sessionId = sessionId,
            controlId = nextControlId(),
        ))
    }

    fun respondToApproval(
        runId: String,
        sessionId: String,
        decisionId: String,
        decision: String,
        optionId: String? = null,
        reason: String? = null,
    ) {
        send(WSClientMessage(
            type = WSClientMessage.TOOL_APPROVAL_RESPONSE,
            runId = runId,
            sessionId = sessionId,
            decisionId = decisionId,
            controlId = nextControlId(),
            decision = decision,
            optionId = optionId,
            reason = reason,
        ))
    }

    fun respondToUserInput(
        runId: String,
        sessionId: String,
        decisionId: String,
        answers: List<dev.memoh.core.model.WSUserInputAnswer>?,
        canceled: Boolean = false,
        reason: String? = null,
    ) {
        send(WSClientMessage(
            type = WSClientMessage.USER_INPUT_RESPONSE,
            runId = runId,
            sessionId = sessionId,
            decisionId = decisionId,
            controlId = nextControlId(),
            answers = answers,
            canceled = canceled,
            reason = reason,
        ))
    }

    private fun nextControlId(): String = "ctl-${controlCounter.incrementAndGet()}-${System.currentTimeMillis()}"

    /** Frames still awaiting acknowledgement — the UI shows these as unconfirmed. */
    val pendingKeys: Set<String> get() = pending.keys.toSet()

    // -- socket callback ----------------------------------------------------

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch {
                mutex.withLock {
                    if (closed || socket !== webSocket) { webSocket.cancel(); return@withLock }
                    reconnectDelayMs.set(INITIAL_RECONNECT_MS)
                    setStatus(SocketStatus.Connected)

                    // 1. Replay unacknowledged reliable frames. The server
                    //    deduplicates on their ids.
                    pending.values.forEach { sendPending(webSocket, it) }

                    // 2. Flush queued fire-and-forget frames.
                    while (true) {
                        val next = sendQueue.tryReceive().getOrNull() ?: break
                        webSocket.send(next)
                    }

                    // 3. Re-subscribe so a fresh authoritative snapshot arrives
                    //    before the user can send anything.
                    subscribedSessionId?.let {
                        webSocket.send(
                            json.encodeToString(
                                WSClientMessage.serializer(),
                                WSClientMessage(
                                    type = WSClientMessage.RUNTIME_SUBSCRIBE,
                                    sessionId = it,
                                    cursor = cursor,
                                ),
                            ),
                        )
                    }
                    observedSessionIds.filter { it != subscribedSessionId }.forEach { id ->
                        webSocket.send(json.encodeToString(WSClientMessage.serializer(),
                            WSClientMessage(type = WSClientMessage.RUNTIME_SUBSCRIBE, sessionId = id)))
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed || socket !== webSocket) return
            val event = try {
                json.decodeFromString(UIStreamEvent.serializer(), text)
            } catch (e: Exception) {
                // An unparsable frame is not worth killing the connection over.
                return
            }
            if (!event.isKnown) return

            // Drop the pending entry the moment the server acknowledges it, so a
            // later reconnect cannot re-send settled work.
            acknowledgedRequestKey(event)?.let { pending.remove(it) }

            event.seq?.takeIf { event.sessionId == subscribedSessionId }?.let { seq ->
                event.epoch?.let { epoch ->
                    cursor = dev.memoh.core.model.RuntimeCursor(epoch = epoch, seq = seq)
                }
            }

            // A gap means the client is desynchronised; the server never
            // back-fills, so the only recovery is a fresh subscribe.
            if (event.type == UIStreamEvent.RUNTIME_DROPPED) {
                onResubscribeNeeded()
            }

            onEvent(event)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch {
                mutex.withLock {
                    if (closed || socket !== webSocket) return@withLock
                    socket = null
                    setStatus(SocketStatus.Reconnecting)
                    scheduleReconnectLocked()
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch {
                mutex.withLock {
                    if (closed || socket !== webSocket) return@withLock
                    socket = null
                    setStatus(SocketStatus.Reconnecting)
                    scheduleReconnectLocked()
                }
            }
        }
    }

    private fun scheduleReconnectLocked() {
        if (closed) return
        if (reconnectJob?.isActive == true) return
        val delayMs = reconnectDelayMs.get()
        reconnectDelayMs.set((delayMs * RECONNECT_BACKOFF).toLong().coerceAtMost(MAX_RECONNECT_MS))
        reconnectJob = scope.launch {
            delay(delayMs)
            mutex.withLock {
                reconnectJob = null
                if (!closed) openLocked()
            }
        }
    }

    private fun setStatus(next: SocketStatus) {
        if (status == next) return
        status = next
        onStatus(next)
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        // OkHttp closes the WebSocket if its send queue grows beyond 16 MiB.
        private const val MAX_MESSAGE_BYTES = 16L * 1024 * 1024
        private const val INITIAL_RECONNECT_MS = 1_000L
        private const val RECONNECT_BACKOFF = 1.5
        private const val MAX_RECONNECT_MS = 10_000L
    }
}
