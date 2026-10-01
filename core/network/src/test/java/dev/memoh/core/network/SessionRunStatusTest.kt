package dev.memoh.core.network

import dev.memoh.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SessionRunStatusTest {
    private fun snapshot(seq: Long, run: RuntimeCurrentRunView?) = UIStreamEvent(
        type = UIStreamEvent.RUNTIME_SNAPSHOT, sessionId = "session", epoch = "epoch", seq = seq,
        snapshot = RuntimeSnapshot(sessionId = "session", epoch = "epoch", seq = seq, currentRunView = run))
    private fun delta(seq: Long, value: RuntimeDelta) = UIStreamEvent(
        type = UIStreamEvent.RUNTIME_DELTA, sessionId = "session", epoch = "epoch", seq = seq, delta = value)
    private fun run(status: String = RunStatus.RUNNING) = RuntimeCurrentRunView(runId = "run", turnId = "turn", status = status)

    @Test fun `active wait and completion are tracked without retaining message contents`() {
        var state = SessionRunStatusReducer.apply(RuntimeState.EMPTY, snapshot(1, run().copy(
            messages = listOf(UIMessage(id = 1, type = "text", content = "Long transcript")),
            requestUserTurn = UIUserTurn(turnId = "user-turn", text = "User request"))))
        assertTrue(state.isRunning)
        assertTrue(state.run?.messages.isNullOrEmpty())
        assertNull(state.run?.requestUserTurn)
        state = SessionRunStatusReducer.apply(state, delta(2, RuntimeDelta(
            messageAppends = listOf(RuntimeMessageAppend(1, "text", "Streaming text")))))
        assertEquals(2L, state.seq)
        assertTrue(state.run?.messages.isNullOrEmpty())
        state = SessionRunStatusReducer.apply(state, delta(3, RuntimeDelta(
            run = RuntimeCurrentRunPatch("run", status = RunStatus.WAITING_DECISION))))
        assertEquals(RunStatus.WAITING_DECISION, state.run?.status)
        state = SessionRunStatusReducer.apply(state, delta(4, RuntimeDelta(
            run = RuntimeCurrentRunPatch("run", status = RunStatus.COMPLETED))))
        assertFalse(state.isRunning)
    }

    @Test fun `empty and configuration-only snapshots are never shown as active tasks`() {
        assertFalse(SessionRunStatusReducer.apply(RuntimeState.EMPTY, snapshot(1, null)).isRunning)
        assertFalse(SessionRunStatusReducer.apply(RuntimeState.EMPTY, snapshot(2, run().copy(configurationOnly = true))).isRunning)
    }

    @Test fun `gaps request authoritative state and old snapshots cannot resurrect completion`() {
        val completed = SessionRunStatusReducer.apply(RuntimeState.EMPTY, snapshot(8, run(RunStatus.COMPLETED)))
        assertFalse(SessionRunStatusReducer.apply(completed, snapshot(2, run())).isRunning)
        assertTrue(SessionRunStatusReducer.apply(completed, delta(10, RuntimeDelta())).needsSnapshot)
        assertTrue(SessionRunStatusReducer.apply(completed, UIStreamEvent(
            type = UIStreamEvent.RUNTIME_DROPPED, sessionId = "session")).needsSnapshot)
    }
}
