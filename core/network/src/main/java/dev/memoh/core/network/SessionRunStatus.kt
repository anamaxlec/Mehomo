package dev.memoh.core.network

import dev.memoh.core.model.*
import kotlinx.coroutines.CoroutineScope

/** Uses the same authenticated transport as chat, with no turn-opening frames. */
fun MemohApi.sessionStatusSocket(botId: String, scope: CoroutineScope,
    onEvent: (UIStreamEvent) -> Unit, onStatus: (SocketStatus) -> Unit): ChatSocket =
    ChatSocket(networkClient, json, scope, buildRequest = { socketRequest("/bots/$botId/web/ws") },
        onEvent = onEvent, onStatus = onStatus)

/** Keeps only run identity/status while retaining the established sequencing rules. */
object SessionRunStatusReducer {
    fun apply(state: RuntimeState, event: UIStreamEvent): RuntimeState {
        val session = event.sessionId ?: return state
        if (event.type == UIStreamEvent.RUNTIME_DROPPED) return state.copy(needsSnapshot = true)
        val epoch = event.epoch ?: return state
        val seq = event.seq ?: return state
        return when (event.type) {
            UIStreamEvent.RUNTIME_SNAPSHOT -> event.snapshot?.let {
                RuntimeReducer.snapshot(state, session, epoch, seq,
                    it.copy(currentRunView = it.currentRunView?.summary()))
            } ?: state
            UIStreamEvent.RUNTIME_DELTA -> event.delta?.let {
                RuntimeReducer.delta(state, session, epoch, seq,
                    RuntimeDelta(currentRunView = it.currentRunView?.summary(), run = it.run))
            } ?: state
            else -> state
        }
    }

    private fun RuntimeCurrentRunView.summary() = RuntimeCurrentRunView(
        runId = runId, turnId = turnId, status = status, configurationOnly = configurationOnly)
}
