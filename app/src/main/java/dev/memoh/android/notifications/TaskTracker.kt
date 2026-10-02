package dev.memoh.android.notifications

import dev.memoh.core.model.*
import dev.memoh.core.network.RuntimeReducer

data class TrackedTask(val runId: String, val phase: String, val terminal: Boolean,
    val startedAt: String?, val decisionId: String? = null)

data class TaskUpdate(val task: TrackedTask?, val alert: Boolean = false)

/** Keeps server cursors and notification identities across transport reconnects. */
class TaskTracker {
    val states = mutableMapOf<String, RuntimeState>()
    private val activeRuns = mutableSetOf<String>()
    private val alerted = mutableSetOf<String>()
    private val displayed = mutableMapOf<String, TrackedTask>()

    fun disconnected() { states.replaceAll { _, state -> state.copy(needsSnapshot = true) }; displayed.clear() }
    fun activeTasks(): Map<String, TrackedTask> = displayed.filterValues { !it.terminal }
    fun accepted(runId: String) { activeRuns.add(runId) }

    fun apply(event: UIStreamEvent): TaskUpdate? {
        val session = event.sessionId ?: return null
        val previous = states[session] ?: RuntimeState.EMPTY
        val next = when (event.type) {
            UIStreamEvent.RUNTIME_SNAPSHOT -> event.snapshot?.let {
                RuntimeReducer.snapshot(previous, session, event.epoch ?: return null, event.seq ?: return null, it)
            } ?: return null
            UIStreamEvent.RUNTIME_DELTA -> event.delta?.let {
                RuntimeReducer.delta(previous, session, event.epoch ?: return null, event.seq ?: return null, it)
            } ?: return null
            UIStreamEvent.RUNTIME_DROPPED -> previous.copy(needsSnapshot = true)
            else -> return null
        }
        states[session] = next
        if (next.needsSnapshot) return null
        val run = next.run?.takeUnless { it.configurationOnly == true }
        if (run == null) return if (displayed.remove(session) != null) TaskUpdate(null) else null
        val approval = run.safeMessages.firstNotNullOfOrNull { it.approval?.takeIf { a -> a.isActionable } }
        val input = run.safeMessages.firstNotNullOfOrNull { it.userInput?.takeIf { q -> q.isActionable } }
        val decision = approval?.let { "approval:${it.approvalId}" } ?: input?.let { "input:${it.userInputId}" }
        val phase = when {
            run.isTerminal -> when (run.status) {
                RunStatus.COMPLETED -> "本轮已结束"
                RunStatus.ABORTED -> "已停止"
                RunStatus.LOST -> "运行状态丢失"
                else -> "运行失败"
            }
            approval != null -> "等待批准"
            input != null -> "等待回答"
            run.status == RunStatus.WAITING_DECISION -> "等待处理"
            run.status == RunStatus.ADMITTING -> "准备执行"
            run.status == RunStatus.ABORTING -> "正在停止"
            run.status == RunStatus.FINISHING -> "正在收尾"
            else -> "执行中"
        }
        // Never announce historic terminal runs discovered on the first snapshot.
        if (run.isTerminal && run.runId !in activeRuns)
            return if (displayed.remove(session) != null) TaskUpdate(null) else null
        if (!run.isTerminal) activeRuns.add(run.runId)
        val task = TrackedTask(run.runId, phase, run.isTerminal, run.startedAt, decision)
        if (displayed[session] == task) return null
        displayed[session] = task
        val alertKey = when {
            run.isTerminal -> "${run.runId}:terminal"
            decision != null -> "${run.runId}:$decision"
            else -> null
        }
        return TaskUpdate(task, alertKey != null && alerted.add(alertKey))
    }
}
