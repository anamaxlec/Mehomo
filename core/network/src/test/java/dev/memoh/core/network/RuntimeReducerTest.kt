package dev.memoh.core.network

import dev.memoh.core.model.RunStatus
import dev.memoh.core.model.RuntimeCurrentRunPatch
import dev.memoh.core.model.RuntimeCurrentRunView
import dev.memoh.core.model.RuntimeDelta
import dev.memoh.core.model.RuntimeMessageAppend
import dev.memoh.core.model.RuntimeProgressAppend
import dev.memoh.core.model.RuntimeSnapshot
import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.RuntimeSteerTurnView
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UIUserTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge rules are the one place a subtle bug silently corrupts a transcript,
 * so every branch of [RuntimeReducer] is pinned here.
 */
class RuntimeReducerTest {

    private val session = "session-1"
    private val epoch = "epoch-1"

    private fun run(
        runId: String = "run-1",
        status: String = RunStatus.RUNNING,
        messages: List<UIMessage> = emptyList(),
        userTurns: List<UIUserTurn> = emptyList(),
        steerTurns: List<RuntimeSteerTurnView> = emptyList(),
    ) = RuntimeCurrentRunView(
        runId = runId,
        turnId = "turn-1",
        status = status,
        messages = messages,
        userTurns = userTurns,
        steerTurns = steerTurns,
    )

    private fun state(
        seq: Long = 5L,
        run: RuntimeCurrentRunView? = run(),
        needsSnapshot: Boolean = false,
    ) = RuntimeState(sessionId = session, epoch = epoch, seq = seq, run = run, needsSnapshot = needsSnapshot)

    private fun snapshot(seq: Long, view: RuntimeCurrentRunView?) = RuntimeSnapshot(
        sessionId = session,
        epoch = epoch,
        seq = seq,
        currentRunView = view,
    )

    // -- snapshot acceptance -------------------------------------------------

    @Test
    fun `snapshot with mismatched envelope requests a new snapshot`() {
        val result = RuntimeReducer.snapshot(state(), session, epoch, 7L, snapshot(9L, run()))
        assertTrue(result.needsSnapshot)
    }

    @Test
    fun `snapshot with mismatched session requests a new snapshot`() {
        val result = RuntimeReducer.snapshot(state(), "other-session", epoch, 9L, snapshot(9L, run()))
        assertTrue(result.needsSnapshot)
    }

    @Test
    fun `older snapshot does not roll back settled state`() {
        val settled = state(seq = 20L, run = run(status = RunStatus.COMPLETED))
        val result = RuntimeReducer.snapshot(settled, session, epoch, 10L, snapshot(10L, run(status = RunStatus.RUNNING)))
        assertEquals(settled, result)
    }

    @Test
    fun `newer snapshot replaces the view`() {
        val result = RuntimeReducer.snapshot(state(), session, epoch, 9L, snapshot(9L, run(runId = "run-2")))
        assertFalse(result.needsSnapshot)
        assertEquals(9L, result.seq)
        assertEquals("run-2", result.run?.runId)
    }

    // -- delta sequencing ---------------------------------------------------

    @Test
    fun `delta with a gap requests a new snapshot instead of applying`() {
        // seq 8 arrives while we hold 5 — the server never back-fills.
        val result = RuntimeReducer.delta(state(seq = 5L), session, epoch, 8L, RuntimeDelta())
        assertTrue(result.needsSnapshot)
    }

    @Test
    fun `delta with a stale seq requests a new snapshot`() {
        val result = RuntimeReducer.delta(state(seq = 5L), session, epoch, 3L, RuntimeDelta())
        assertTrue(result.needsSnapshot)
    }

    @Test
    fun `delta with a different epoch requests a new snapshot`() {
        val result = RuntimeReducer.delta(state(), session, "epoch-2", 6L, RuntimeDelta())
        assertTrue(result.needsSnapshot)
    }

    @Test
    fun `delta after needsSnapshot stays desynchronised`() {
        val result = RuntimeReducer.delta(
            state(needsSnapshot = true), session, epoch, 6L, RuntimeDelta(),
        )
        assertTrue(result.needsSnapshot)
    }

    @Test
    fun `consecutive delta advances seq`() {
        val result = RuntimeReducer.delta(state(seq = 5L), session, epoch, 6L, RuntimeDelta())
        assertFalse(result.needsSnapshot)
        assertEquals(6L, result.seq)
    }

    // -- full view replacement ----------------------------------------------

    @Test
    fun `current_run_view replaces the whole view and ignores partial fields`() {
        val replacement = run(runId = "run-9", status = RunStatus.WAITING_DECISION)
        val delta = RuntimeDelta(
            currentRunView = replacement,
            run = RuntimeCurrentRunPatch(runId = "run-1", status = RunStatus.ERRORED),
        )
        val result = RuntimeReducer.delta(state(seq = 5L), session, epoch, 6L, delta)
        assertEquals("run-9", result.run?.runId)
        assertEquals(RunStatus.WAITING_DECISION, result.run?.status)
    }

    // -- run patch ----------------------------------------------------------

    @Test
    fun `run patch for an unknown run requests a new snapshot`() {
        val delta = RuntimeDelta(run = RuntimeCurrentRunPatch(runId = "other-run", status = RunStatus.ERRORED))
        val result = RuntimeReducer.delta(state(seq = 5L), session, epoch, 6L, delta)
        assertTrue(result.needsSnapshot)
    }

    @Test
    fun `run patch updates only the fields it carries`() {
        val delta = RuntimeDelta(run = RuntimeCurrentRunPatch(runId = "run-1", status = RunStatus.ABORTING))
        val result = RuntimeReducer.delta(state(seq = 5L), session, epoch, 6L, delta)
        assertEquals(RunStatus.ABORTING, result.run?.status)
        assertEquals("turn-1", result.run?.turnId)
    }

    // -- message appends ----------------------------------------------------

    @Test
    fun `text appends accumulate into one block`() {
        var s = state(seq = 5L, run = run())
        listOf("Hel", "lo ", "world").forEachIndexed { i, chunk ->
            s = RuntimeReducer.delta(
                s, session, epoch, s.seq + 1,
                RuntimeDelta(messageAppends = listOf(RuntimeMessageAppend(id = 1, type = "text", content = chunk))),
            )
        }
        assertEquals(1, s.run?.safeMessages?.size)
        assertEquals("Hello world", s.run?.safeMessages?.first()?.content)
    }

    @Test
    fun `appends for different ids create separate blocks`() {
        val delta = RuntimeDelta(
            messageAppends = listOf(
                RuntimeMessageAppend(id = 1, type = "reasoning", content = "think"),
                RuntimeMessageAppend(id = 2, type = "text", content = "answer"),
            ),
        )
        val result = RuntimeReducer.delta(state(seq = 5L), session, epoch, 6L, delta)
        assertEquals(listOf(1, 2), result.run?.safeMessages?.map { it.id })
    }

    @Test
    fun `reset_messages clears the transcript before applying the frame`() {
        val delta = RuntimeDelta(
            resetMessages = true,
            messageAppends = listOf(RuntimeMessageAppend(id = 7, type = "text", content = "fresh")),
        )
        val result = RuntimeReducer.delta(
            state(seq = 5L, run = run(messages = listOf(UIMessage(id = 1, type = "text", content = "old")))),
            session, epoch, 6L, delta,
        )
        assertEquals(listOf(7), result.run?.safeMessages?.map { it.id })
    }

    @Test
    fun `a hundred appends reconstruct the full text`() {
        var s = state(seq = 5L, run = run())
        val expected = StringBuilder()
        repeat(100) { i ->
            val chunk = "chunk$i-"
            expected.append(chunk)
            s = RuntimeReducer.delta(
                s, session, epoch, s.seq + 1,
                RuntimeDelta(messageAppends = listOf(RuntimeMessageAppend(id = 1, type = "text", content = chunk))),
            )
        }
        assertEquals(expected.toString(), s.run?.safeMessages?.first()?.content)
    }

    // -- progress -----------------------------------------------------------

    @Test
    fun `progress appends accumulate on the matching tool block only`() {
        val base = run(
            messages = listOf(
                UIMessage(id = 1, type = UIMessage.TYPE_TOOL, toolCallId = "call-1", name = "bash"),
                UIMessage(id = 2, type = "text", content = "text"),
            ),
        )
        val delta = RuntimeDelta(
            progressAppends = listOf(
                RuntimeProgressAppend(id = 1, progress = kotlinx.serialization.json.JsonPrimitive("step 1")),
                RuntimeProgressAppend(id = 2, progress = kotlinx.serialization.json.JsonPrimitive("ignored")),
            ),
        )
        val result = RuntimeReducer.delta(state(seq = 5L, run = base), session, epoch, 6L, delta)
        val tool = result.run?.safeMessages?.first { it.id == 1 }
        val text = result.run?.safeMessages?.first { it.id == 2 }
        assertEquals(1, tool?.progress?.size)
        assertNull(text?.progress)
    }

    // -- upserts ------------------------------------------------------------

    @Test
    fun `upsert by id replaces the block`() {
        val base = run(messages = listOf(UIMessage(id = 1, type = "text", content = "partial")))
        val delta = RuntimeDelta(
            messageUpserts = listOf(UIMessage(id = 1, type = "text", content = "settled")),
        )
        val result = RuntimeReducer.delta(state(seq = 5L, run = base), session, epoch, 6L, delta)
        assertEquals("settled", result.run?.safeMessages?.first()?.content)
    }

    @Test
    fun `upsert matches a tool block by tool_call_id and keeps the existing row id`() {
        val base = run(
            messages = listOf(
                UIMessage(id = 3, type = UIMessage.TYPE_TOOL, toolCallId = "call-1", name = "bash", running = true),
            ),
        )
        // The server's settled copy carries a different row id.
        val delta = RuntimeDelta(
            messageUpserts = listOf(
                UIMessage(id = 9, type = UIMessage.TYPE_TOOL, toolCallId = "call-1", name = "bash", running = false, output = kotlinx.serialization.json.JsonPrimitive("done")),
            ),
        )
        val result = RuntimeReducer.delta(state(seq = 5L, run = base), session, epoch, 6L, delta)
        val messages = result.run?.safeMessages.orEmpty()
        assertEquals(1, messages.size)
        // Stable UI key preserved: the original row id wins.
        assertEquals(3, messages.first().id)
        assertFalse(messages.first().running)
    }

    @Test
    fun `upsert of an unknown block appends it`() {
        val delta = RuntimeDelta(
            messageUpserts = listOf(UIMessage(id = 4, type = "notice", content = "degraded")),
        )
        val result = RuntimeReducer.delta(state(seq = 5L, run = run()), session, epoch, 6L, delta)
        assertEquals(listOf(4), result.run?.safeMessages?.map { it.id })
    }

    @Test
    fun `messages stay ordered by id after mixed merges`() {
        val base = run(messages = listOf(UIMessage(id = 5, type = "text", content = "five")))
        val delta = RuntimeDelta(
            messageAppends = listOf(RuntimeMessageAppend(id = 2, type = "text", content = "two")),
            messageUpserts = listOf(UIMessage(id = 8, type = "text", content = "eight")),
        )
        val result = RuntimeReducer.delta(state(seq = 5L, run = base), session, epoch, 6L, delta)
        assertEquals(listOf(2, 5, 8), result.run?.safeMessages?.map { it.id })
    }

    // -- steer & user turns -------------------------------------------------

    @Test
    fun `steer upsert then removal drops the entry`() {
        val base = run(
            steerTurns = listOf(
                RuntimeSteerTurnView(itemId = "s1", status = "claimed", text = "insert", afterMessageId = 1),
            ),
        )
        val delta = RuntimeDelta(steerTurnRemovals = listOf("s1"))
        val result = RuntimeReducer.delta(state(seq = 5L, run = base), session, epoch, 6L, delta)
        assertTrue(result.run?.steerTurns.orEmpty().isEmpty())
    }

    @Test
    fun `steer upsert replaces an existing entry in place`() {
        val base = run(
            steerTurns = listOf(
                RuntimeSteerTurnView(itemId = "s1", status = "claimed", text = "insert"),
            ),
        )
        val delta = RuntimeDelta(
            steerTurnUpserts = listOf(
                RuntimeSteerTurnView(itemId = "s1", status = "applied", text = "insert", turnId = "turn-9"),
            ),
        )
        val result = RuntimeReducer.delta(state(seq = 5L, run = base), session, epoch, 6L, delta)
        assertEquals(1, result.run?.steerTurns?.size)
        assertEquals("applied", result.run?.steerTurns?.first()?.status)
        assertEquals("turn-9", result.run?.steerTurns?.first()?.turnId)
    }

    @Test
    fun `user turn upsert replaces by turn id`() {
        val base = run(userTurns = listOf(UIUserTurn(turnId = "t1", text = "hi")))
        val delta = RuntimeDelta(userTurnUpserts = listOf(UIUserTurn(turnId = "t1", text = "hi there")))
        val result = RuntimeReducer.delta(state(seq = 5L, run = base), session, epoch, 6L, delta)
        assertEquals(1, result.run?.userTurns?.size)
        assertEquals("hi there", result.run?.userTurns?.first()?.text)
    }

    // -- edge cases ---------------------------------------------------------

    @Test
    fun `delta against an empty run still advances the cursor`() {
        val result = RuntimeReducer.delta(
            RuntimeState(sessionId = session, epoch = epoch, seq = 5L, run = null),
            session, epoch, 6L, RuntimeDelta(),
        )
        assertFalse(result.needsSnapshot)
        assertEquals(6L, result.seq)
        assertNull(result.run)
    }

    @Test
    fun `a settled run is not resurrected by a status patch from the same run`() {
        // Guards the "late frame flips a completed run back to running" class of
        // bug: the patch only touches fields it carries, so an absent status
        // cannot reopen the run.
        val settled = state(seq = 5L, run = run(status = RunStatus.COMPLETED))
        val result = RuntimeReducer.delta(
            settled, session, epoch, 6L,
            RuntimeDelta(run = RuntimeCurrentRunPatch(runId = "run-1", error = null)),
        )
        assertEquals(RunStatus.COMPLETED, result.run?.status)
    }
}
