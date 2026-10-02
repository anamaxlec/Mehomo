package dev.memoh.android.notifications

import dev.memoh.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TaskTrackerTest {
    private fun snapshot(status: String, seq: Long = 1, runId: String = "run-1", messages: List<UIMessage> = emptyList()) =
        UIStreamEvent(UIStreamEvent.RUNTIME_SNAPSHOT, sessionId = "session-1", epoch = "epoch", seq = seq,
            snapshot = RuntimeSnapshot(sessionId = "session-1", epoch = "epoch", seq = seq,
                currentRunView = RuntimeCurrentRunView(runId = runId, turnId = "turn-1", status = status, messages = messages)))

    @Test fun `historic completion does not produce a notification`() {
        assertNull(TaskTracker().apply(snapshot(RunStatus.COMPLETED)))
    }

    @Test fun `a locally admitted short run alerts even if its first snapshot is already terminal`() {
        val tracker = TaskTracker()
        tracker.accepted("run-1")
        assertTrue(tracker.apply(snapshot(RunStatus.COMPLETED))!!.alert)
    }

    @Test fun `completion after a tracked run alerts once across reconnect`() {
        val tracker = TaskTracker()
        assertFalse(tracker.apply(snapshot(RunStatus.RUNNING))!!.alert)
        tracker.disconnected()
        assertTrue(tracker.apply(snapshot(RunStatus.COMPLETED, 2))!!.alert)
        tracker.disconnected()
        assertFalse(tracker.apply(snapshot(RunStatus.COMPLETED, 2))!!.alert)
    }

    @Test fun `reconnect restores the running display even without a status change`() {
        val tracker = TaskTracker()
        tracker.apply(snapshot(RunStatus.RUNNING))
        tracker.disconnected()
        assertEquals("执行中", tracker.apply(snapshot(RunStatus.RUNNING))!!.task!!.phase)
    }

    @Test fun `approval and user input have distinct identities while status is unchanged`() {
        val tracker = TaskTracker()
        val approval = UIMessage(id = 1, type = "tool", approval = UIToolApproval(approvalId = "approval-1", status = "pending"))
        val question = UIMessage(id = 2, type = "user_input", userInput = UIUserInput(userInputId = "input-1", status = "pending"))
        val first = tracker.apply(snapshot(RunStatus.WAITING_DECISION, messages = listOf(approval)))!!
        assertEquals("等待批准", first.task!!.phase); assertTrue(first.alert)
        tracker.disconnected()
        assertFalse(tracker.apply(snapshot(RunStatus.WAITING_DECISION, messages = listOf(approval)))!!.alert)
        val next = tracker.apply(snapshot(RunStatus.WAITING_DECISION, 2, messages = listOf(question)))!!
        assertEquals("等待回答", next.task!!.phase); assertTrue(next.alert)
    }

    @Test fun `a gap cannot be mistaken for completion`() {
        val tracker = TaskTracker()
        tracker.apply(snapshot(RunStatus.RUNNING))
        val update = tracker.apply(UIStreamEvent(UIStreamEvent.RUNTIME_DELTA, sessionId = "session-1", epoch = "epoch", seq = 3,
            delta = RuntimeDelta(run = RuntimeCurrentRunPatch("run-1", status = RunStatus.COMPLETED))))
        assertNull(update)
        assertTrue(tracker.states["session-1"]!!.needsSnapshot)
    }

    @Test fun `stopped and failed runs are never labelled as successful`() {
        listOf(RunStatus.ABORTED to "已停止", RunStatus.ERRORED to "运行失败", RunStatus.LOST to "运行状态丢失").forEach { (status, label) ->
            val tracker = TaskTracker()
            tracker.apply(snapshot(RunStatus.RUNNING))
            assertEquals(label, tracker.apply(snapshot(status, 2))!!.task!!.phase)
        }
    }
}
