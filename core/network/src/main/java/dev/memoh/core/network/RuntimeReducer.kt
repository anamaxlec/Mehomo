package dev.memoh.core.network

import dev.memoh.core.model.RuntimeCurrentRunView
import dev.memoh.core.model.RuntimeDelta
import dev.memoh.core.model.RuntimeSnapshot
import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.UIMessage

/**
 * Folds runtime frames into a single authoritative run view.
 *
 * Pure and side-effect free so the merge rules can be tested directly — this is
 * the one piece of the protocol where a subtle mistake corrupts the transcript,
 * and it is cheaper to prove here than on a device.
 *
 * The rules, in order:
 *  1. A snapshot is only accepted when its inner identity matches the event
 *     envelope, and an older snapshot must not roll back settled state.
 *  2. A delta is only accepted when its epoch matches and its seq is exactly
 *     current + 1. The server never back-fills gaps, so a mismatch means the
 *     client is desynchronised and must re-subscribe.
 *  3. A `current_run_view` replaces the whole view; otherwise the run is patched.
 *  4. Message merge order is: reset, appends, progress, then upserts — upserts
 *     are authoritative and land last, because they carry the settled form.
 */
object RuntimeReducer {

    /**
     * @return the new state, or `state` with `needsSnapshot = true` when the
     *         frame cannot be applied.
     */
    fun snapshot(
        state: RuntimeState,
        eventSessionId: String,
        eventEpoch: String,
        eventSeq: Long,
        snapshot: RuntimeSnapshot,
    ): RuntimeState {
        // The envelope and the payload must agree; a mismatch means we cannot
        // trust either, so ask for a fresh snapshot.
        if (snapshot.sessionId != eventSessionId ||
            snapshot.epoch != eventEpoch ||
            snapshot.seq != eventSeq
        ) {
            return state.copy(needsSnapshot = true)
        }
        // Re-subscriptions can overlap. An older snapshot must not resurrect a
        // run or decision that has already settled.
        if (state.sessionId == eventSessionId &&
            state.epoch == eventEpoch &&
            eventSeq < state.seq
        ) {
            return state
        }
        return RuntimeState(
            sessionId = eventSessionId,
            epoch = eventEpoch,
            seq = eventSeq,
            run = snapshot.currentRunView,
            needsSnapshot = false,
        )
    }

    fun delta(
        state: RuntimeState,
        sessionId: String,
        epoch: String,
        seq: Long,
        delta: RuntimeDelta,
    ): RuntimeState {
        if (state.needsSnapshot ||
            state.sessionId != sessionId ||
            state.epoch != epoch ||
            seq != state.seq + 1
        ) {
            return state.copy(needsSnapshot = true)
        }

        // A full view supersedes every partial field in the same frame.
        delta.currentRunView?.let {
            return state.copy(seq = seq, run = it, needsSnapshot = false)
        }

        var run = state.run
        val patch = delta.run
        if (patch != null) {
            if (run == null || patch.runId != run.runId) {
                // A patch for a run we do not hold cannot be applied in isolation.
                return state.copy(needsSnapshot = true)
            }
            run = run.copy(
                status = patch.status ?: run.status,
                errorCode = patch.errorCode ?: run.errorCode,
                error = patch.error ?: run.error,
                updatedAt = patch.updatedAt ?: run.updatedAt,
                ownerLeaseExpiresAt = patch.ownerLeaseExpiresAt ?: run.ownerLeaseExpiresAt,
            )
        }

        if (run != null) {
            run = run.copy(
                messages = mergeMessages(run.safeMessages, delta),
                userTurns = mergeUserTurns(run, delta),
                steerTurns = mergeSteerTurns(run, delta),
            )
        }

        return state.copy(seq = seq, run = run, needsSnapshot = false)
    }

    private fun mergeMessages(current: List<UIMessage>, delta: RuntimeDelta): List<UIMessage> {
        var messages = if (delta.resetMessages) emptyList() else current

        // Streaming text: extend the existing block of the same type, or start one.
        delta.messageAppends.orEmpty().forEach { append ->
            val index = messages.indexOfFirst { it.id == append.id }
            when {
                index >= 0 && messages[index].type == append.type ->
                    messages = messages.toMutableList().also { list ->
                        list[index] = list[index].copy(content = list[index].content + append.content)
                    }
                index < 0 ->
                    messages = messages + UIMessage(
                        id = append.id,
                        type = append.type,
                        content = append.content,
                    )
                // Same id, different type: the server reassigned the slot. The
                // authoritative upsert that follows will settle it.
            }
        }

        // Tool progress accumulates; the input may be refined on each append.
        delta.progressAppends.orEmpty().forEach { append ->
            val index = messages.indexOfFirst { it.id == append.id && it.type == UIMessage.TYPE_TOOL }
            if (index >= 0) {
                val currentMessage = messages[index]
                messages = messages.toMutableList().also { list ->
                    list[index] = currentMessage.copy(
                        input = append.input ?: currentMessage.input,
                        progress = currentMessage.progress.orEmpty() + listOfNotNull(append.progress),
                    )
                }
            }
        }

        // Upserts are authoritative and applied last. They are matched by id, or
        // by tool_call_id for tool blocks whose row id changed — the existing id
        // is kept so the UI's stable key survives.
        delta.messageUpserts.orEmpty().forEach { upsert ->
            val toolCallId = upsert.toolCallId.trim()
            val index = messages.indexOfFirst {
                it.id == upsert.id ||
                    (toolCallId.isNotEmpty() && it.type == UIMessage.TYPE_TOOL &&
                        it.toolCallId.trim() == toolCallId)
            }
            messages = if (index >= 0) {
                messages.toMutableList().also { list ->
                    list[index] = upsert.copy(id = list[index].id)
                }
            } else {
                messages + upsert
            }
        }

        return messages.sortedBy { it.id }
    }

    private fun mergeUserTurns(
        run: RuntimeCurrentRunView,
        delta: RuntimeDelta,
    ): List<dev.memoh.core.model.UIUserTurn> {
        val turns = run.userTurns.orEmpty().toMutableList()
        delta.userTurnUpserts.orEmpty().forEach { turn ->
            val index = turns.indexOfFirst { it.turnId == turn.turnId }
            if (index >= 0) turns[index] = turn else turns.add(turn)
        }
        return turns
    }

    private fun mergeSteerTurns(
        run: RuntimeCurrentRunView,
        delta: RuntimeDelta,
    ): List<dev.memoh.core.model.RuntimeSteerTurnView> {
        val removals = delta.steerTurnRemovals.orEmpty().toSet()
        val turns = run.steerTurns.orEmpty().filterNot { it.itemId in removals }.toMutableList()
        delta.steerTurnUpserts.orEmpty().forEach { turn ->
            val index = turns.indexOfFirst { it.itemId == turn.itemId }
            if (index >= 0) turns[index] = turn else turns.add(turn)
        }
        return turns
    }
}
