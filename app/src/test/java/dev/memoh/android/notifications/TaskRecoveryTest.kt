package dev.memoh.android.notifications

import dev.memoh.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }

    private fun snapshot(status: String, run: String = "run-1", messages: List<UIMessage> = emptyList(), seq: Long = 1) =
        UIStreamEvent(UIStreamEvent.RUNTIME_SNAPSHOT, sessionId = "session-1", epoch = "epoch", seq = seq,
            snapshot = RuntimeSnapshot(sessionId = "session-1", epoch = "epoch", seq = seq,
                currentRunView = RuntimeCurrentRunView(runId = run, turnId = "turn-1", status = status, messages = messages)))

    @Test fun `a run completed while the process was gone alerts once after restoring from disk`() = runBlocking {
        val root = temp.newFolder()
        val before = TaskTracker()
        before.apply(snapshot(RunStatus.RUNNING))
        TaskCheckpointStore(root, json).save("account", "bot", before.checkpoint())

        val store = TaskCheckpointStore(root, json)
        val restored = TaskTracker(store.read("account", "bot"))
        assertTrue(restored.apply(snapshot(RunStatus.COMPLETED))!!.alert)
        store.save("account", "bot", restored.checkpoint())

        val anotherProcess = TaskTracker(TaskCheckpointStore(root, json).read("account", "bot"))
        assertFalse(anotherProcess.apply(snapshot(RunStatus.COMPLETED))!!.alert)
        assertEquals(TaskUpdate(null), anotherProcess.apply(snapshot(RunStatus.COMPLETED, "unrelated-historic-run")))
    }

    @Test fun `admission survives a restart even when no running snapshot was received`() = runBlocking {
        val root = temp.newFolder()
        val admitted = TaskTracker().apply { accepted("session-1", "run-1") }
        TaskCheckpointStore(root, json).save("account", "bot", admitted.checkpoint())
        val restored = TaskTracker(TaskCheckpointStore(root, json).read("account", "bot"))
        val failure = restored.apply(snapshot(RunStatus.ERRORED))!!
        assertTrue(failure.alert)
        assertEquals("运行失败", failure.task!!.phase)
    }

    @Test fun `account and bot checkpoints cannot claim each others historic completions`() = runBlocking {
        val store = TaskCheckpointStore(temp.newFolder(), json)
        val tracker = TaskTracker().apply { accepted("session-1", "run-1") }
        store.save("account", "bot", tracker.checkpoint())
        assertNull(TaskTracker(store.read("other-account", "bot")).apply(snapshot(RunStatus.COMPLETED)))
        assertNull(TaskTracker(store.read("account", "other-bot")).apply(snapshot(RunStatus.COMPLETED)))
    }

    @Test fun `approval alerts survive a restart and only a new decision alerts again`() = runBlocking {
        val root = temp.newFolder()
        val first = UIMessage(1, "tool", approval = UIToolApproval(approvalId = "approval-1", status = "pending"))
        val second = UIMessage(2, "tool", approval = UIToolApproval(approvalId = "approval-2", status = "pending"))
        val tracker = TaskTracker()
        assertTrue(tracker.apply(snapshot(RunStatus.WAITING_DECISION, messages = listOf(first)))!!.alert)
        TaskCheckpointStore(root, json).save("account", "bot", tracker.checkpoint())
        val restored = TaskTracker(TaskCheckpointStore(root, json).read("account", "bot"))
        assertFalse(restored.apply(snapshot(RunStatus.WAITING_DECISION, messages = listOf(first)))!!.alert)
        assertTrue(restored.apply(snapshot(RunStatus.WAITING_DECISION, messages = listOf(second), seq = 2))!!.alert)
    }

    @Test fun `new runs replace old alert history and deleted sessions leave no checkpoint`() = runBlocking {
        val tracker = TaskTracker()
        tracker.apply(snapshot(RunStatus.RUNNING))
        assertTrue(tracker.apply(snapshot(RunStatus.COMPLETED, seq = 2))!!.alert)
        tracker.accepted("session-1", "next-run")
        assertTrue(tracker.checkpoint().getValue("session-1").alerted.isEmpty())
        tracker.forget("session-1")
        val store = TaskCheckpointStore(temp.newFolder(), json)
        store.save("account", "bot", tracker.checkpoint())
        assertTrue(store.read("account", "bot").isEmpty())
        assertTrue(tracker.states.isEmpty())
        assertTrue(tracker.activeTasks().isEmpty())
    }

    @Test fun `unchanged running snapshots do not produce new checkpoint writes`() {
        val tracker = TaskTracker()
        tracker.apply(snapshot(RunStatus.RUNNING))
        val checkpoint = tracker.checkpoint()
        repeat(100) { seq ->
            tracker.apply(snapshot(RunStatus.RUNNING, seq = seq + 2L))
            assertSame(checkpoint, tracker.checkpoint())
        }
    }
}
