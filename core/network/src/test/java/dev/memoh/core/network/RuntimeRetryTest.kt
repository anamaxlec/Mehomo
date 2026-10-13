package dev.memoh.core.network

import dev.memoh.core.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RuntimeRetryTest {
    private fun state(retry: RuntimeRetry? = null) = RuntimeState("s", "e", 1,
        RuntimeCurrentRunView(runId = "r", turnId = "t", retry = retry))
    @Test fun patchesRetainRetryUntilTheServerClearsIt() {
        val retry = RuntimeRetry(1, 3, 5000, "rate_limited", "2026-10-13T00:00:05Z")
        val waiting = RuntimeReducer.delta(state(), "s", "e", 2, RuntimeDelta(run = RuntimeCurrentRunPatch("r", retry = retry)))
        assertEquals(retry, waiting.run?.retry)
        val unrelated = RuntimeReducer.delta(waiting, "s", "e", 3, RuntimeDelta(run = RuntimeCurrentRunPatch("r", updatedAt = "later")))
        assertEquals(retry, unrelated.run?.retry)
        val cleared = RuntimeReducer.delta(unrelated, "s", "e", 4, RuntimeDelta(run = RuntimeCurrentRunPatch("r", clearRetry = true)))
        assertNull(cleared.run?.retry)
    }
    @Test fun terminalPatchAndReplacementSnapshotDoNotKeepOldRetry() {
        val waiting = state(RuntimeRetry(1, 3, reason = "network"))
        val ended = RuntimeReducer.delta(waiting, "s", "e", 2, RuntimeDelta(run = RuntimeCurrentRunPatch("r", status = RunStatus.COMPLETED)))
        assertNull(ended.run?.retry)
        val fresh = RuntimeReducer.snapshot(waiting, "s", "e", 2, RuntimeSnapshot(sessionId = "s", epoch = "e", seq = 2,
            currentRunView = RuntimeCurrentRunView(runId = "r", turnId = "t")))
        assertNull(fresh.run?.retry)
    }
    @Test fun wireRetryCarriesReasonsAndDoesNotInventASuccess() {
        val event = Json.decodeFromString<UIStreamEvent>("""{"type":"runtime_delta","session_id":"s","epoch":"e","seq":2,"delta":{"run":{"run_id":"r","retry":{"attempt":2,"max_attempt":3,"delay_ms":1000,"reason":"stream_incomplete","retry_at":"2026-10-13T00:00:05Z"}}}}""")
        val next = RuntimeReducer.delta(state(), "s", "e", 2, event.delta!!)
        assertTrue(next.isRunning)
        assertTrue(next.run!!.retry!!.description().contains("响应中断"))
        assertTrue(next.run!!.retry!!.description().contains("2/3"))
    }
}
