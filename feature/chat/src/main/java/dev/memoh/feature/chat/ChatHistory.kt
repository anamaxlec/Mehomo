package dev.memoh.feature.chat

import dev.memoh.core.markdown.MemohMarkdown
import dev.memoh.core.model.MemohError
import dev.memoh.core.model.RunStatus
import dev.memoh.core.model.RuntimeCurrentRunView
import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UITurn

/**
 * A logical turn can have several persisted assistant records. The newest keeps the runtime
 * identity through completion and pagination; older records use their own database identity.
 */
internal fun assistantHistoryKeys(history: List<UITurn>): Map<String, String> {
    val seen = mutableSetOf<String>()
    return buildMap {
        history.asReversed().forEach { turn ->
            if (turn.isAssistant) put(turn.listKey,
                if (seen.add(turn.turnId)) turn.turnId else "record:${turn.listKey}")
        }
    }
}

/** The live row can be saved before the corresponding REST history record arrives. */
internal fun ChatUiState.turnsForSavedItems(
    settled: List<UITurn> = settledHistory,
    liveUsers: List<UITurn> = liveUserTurns,
): List<UITurn> = buildList {
    addAll(settled)
    addAll(liveUsers)
    runtime.run?.takeIf { liveMessages.isNotEmpty() }?.let { run ->
        add(UITurn(run.turnId, role = "assistant", timestamp = run.updatedAt.orEmpty(), messages = liveMessages))
    }
}

/** Refresh the newest page without removing pages the reader has already opened. */
internal fun mergeLatestHistory(current: List<UITurn>, latest: List<UITurn>): List<UITurn> {
    if (latest.isEmpty()) return current
    val firstOverlap = current.indexOfFirst { old -> latest.any { fresh ->
        old.listKey == fresh.listKey || old.role == fresh.role && old.turnId == fresh.turnId &&
            (old.id.isNullOrBlank() || fresh.id.isNullOrBlank())
    } }
    val older = if (firstOverlap >= 0) current.take(firstOverlap)
        else latest.firstNotNullOfOrNull { it.turnPosition }?.let { first ->
            current.filter { (it.turnPosition ?: Int.MAX_VALUE) < first }
        } ?: current
    return (older + latest).distinctBy { it.listKey }
}

internal data class StoppedSend(val runId: String, val turnId: String, val draft: PendingTurn)

internal fun RuntimeCurrentRunView.hasVisibleOutput(): Boolean =
    safeMessages.any { it.type != UIMessage.TYPE_STATUS && it.type != UIMessage.TYPE_ERROR }

/** Keep rendered output through a completion/new-run frame until REST history arrives. */
internal fun ChatUiState.withRuntime(next: RuntimeState, awaitingSnapshot: Boolean = this.awaitingSnapshot): ChatUiState {
    val nextRun = next.run?.takeUnless { it.runId == discardedRunId }
    val retryAdmitted = regeneration?.let { retry -> nextRun != null &&
        (nextRun.invocationId == retry.invocationId || nextRun.turnId == retry.acceptedTurnId) } == true
    val anchorIndex = nextRun?.operation?.replaceFromMessageId?.takeIf(String::isNotBlank)
        ?.let { anchor -> history.indexOfFirst { it.id == anchor }.takeIf { it >= 0 } }
        ?: if (retryAdmitted) history.indexOfFirst { turn ->
            regeneration?.editFromMessageId?.let { turn.id == it }
                ?: (turn.isAssistant && turn.turnId == regeneration?.turnId)
        }.takeIf { it >= 0 } else null
    val outgoing = when {
        nextRun?.isTerminal == true && nextRun.safeMessages.isNotEmpty() -> nextRun
        nextRun?.isTerminal == true || runtime.run?.turnId != nextRun?.turnId -> runtime.run
        else -> null
    }?.takeIf { it.configurationOnly != true && it.runId != discardedRunId && it.safeMessages.isNotEmpty() &&
        (anchorIndex == null || it.runId == nextRun?.runId) }
    val prefix = anchorIndex?.let(history::take) ?: history
    val retained = if (outgoing == null) prefix else prefix.toMutableList().apply {
        val users = outgoing.userTurns?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(outgoing.requestUserTurn ?: outgoing.operation?.replacementUserTurn)
        val insertAt = this.indexOfFirst { it.isAssistant && it.turnId == outgoing.turnId }
            .takeIf { it >= 0 } ?: this.size
        val missingUsers = users.map { user ->
            UITurn(turnId = user.turnId, turnPosition = user.turnPosition, role = "user", id = user.id,
                text = user.text, attachments = user.attachments, timestamp = user.timestamp,
                userMessageKind = user.userMessageKind, skillActivation = user.skillActivation,
                reply = user.reply, forward = user.forward, senderDisplayName = user.senderDisplayName)
        }.filterNot { user -> this.any { it.isUser && (it.listKey == user.listKey || user.id == null && it.turnId == user.turnId) } }
        this.addAll(insertAt, missingUsers)
        val assistantIndex = this.indexOfFirst { it.isAssistant && it.turnId == outgoing.turnId }
        if (assistantIndex >= 0) {
            val persisted = this[assistantIndex]
            // Completed snapshots contain original model text; persisted history
            // already includes archived image URLs and publication failures.
            if (!(outgoing.status == "completed" && outgoing.operation == null &&
                    !persisted.id.isNullOrBlank() && persisted.safeMessages.any { message ->
                        message.type == "text" && MemohMarkdown.hasPublishedMedia(message.content)
                    })) {
                this[assistantIndex] = persisted.copy(messages = outgoing.safeMessages)
            }
        } else {
            this += UITurn(turnId = outgoing.turnId, turnPosition = outgoing.turnPosition,
                role = "assistant", messages = outgoing.safeMessages, timestamp = outgoing.updatedAt.orEmpty())
        }
    }
    val updated = copy(runtime = next, history = retained, awaitingSnapshot = awaitingSnapshot,
        regeneration = regeneration.takeUnless { retryAdmitted })
    if (pending.isEmpty()) return updated
    val visibleUsers = updated.history.filter { it.isUser } + updated.liveUserTurns
    return updated.copy(pending = pending.filterNot { pending ->
        visibleUsers.any { user -> user.turnId == pending.acceptedTurnId } ||
            next.run?.invocationId == pending.invocationId && visibleUsers.any { it.turnId == next.run?.turnId }
    })
}

internal fun mergeDraftText(previous: String, current: String): String =
    listOf(previous, current).filter(String::isNotBlank).joinToString("\n")

internal fun List<UITurn>.withoutLiveAssistant(turnId: String?): List<UITurn> =
    if (turnId == null || none { it.isAssistant && it.turnId == turnId }) this
    else filterNot { it.isAssistant && it.turnId == turnId }

internal data class HistoryUpdate(val state: ChatUiState, val restoredDraft: Boolean)

internal fun ChatUiState.withHistoryPage(
    page: List<UITurn>,
    before: String?,
    replace: Boolean,
    abortedSendAtRequest: StoppedSend?,
): HistoryUpdate {
    val run = runtime.run
    val restore = abortedSendAtRequest?.takeIf { stop ->
        before == null && run?.runId == stop.runId && run.status == RunStatus.ABORTED &&
            !run.hasVisibleOutput() && page.none { it.turnId == stop.turnId }
    }
    val rollback = run?.takeIf {
        before == null && it.isTerminal && it.status != RunStatus.COMPLETED &&
            !it.hasVisibleOutput() && it.operation?.kind == "edit" &&
            page.any { turn -> turn.id == it.operation?.replaceFromMessageId && turn.isUser } &&
            page.none { turn -> turn.turnId == it.turnId }
    }
    val editError = rollback?.takeIf { it.status != RunStatus.ABORTED && error == null }
    val editProblem = editError?.let { failed ->
        val details = failed.safeMessages.lastOrNull { it.type == UIMessage.TYPE_ERROR }?.problem
        (failed.errorCode ?: details?.code)?.let { details?.copy(code = it) ?: MemohError(code = it) }
    }
    val discardTurn = restore?.turnId ?: rollback?.turnId
    val existing = if (discardTurn == null) history else history.filterNot { it.turnId == discardTurn }
    val merged = when {
        before != null -> page + existing
        replace || offlineSyncedAt != null -> page
        else -> mergeLatestHistory(existing, page)
    }
    val knownKeys = if (before == null) emptySet() else history.mapTo(HashSet()) { it.listKey }
    return HistoryUpdate(
        state = copy(
            offlineSyncedAt = null,
            history = merged.distinctBy { it.listKey },
            discardedRunId = restore?.runId ?: rollback?.runId ?: discardedRunId,
            draft = restore?.let { mergeDraftText(it.draft.text, draft) } ?: draft,
            attachments = restore?.draft?.attachments?.plus(attachments) ?: attachments,
            error = if (editError == null) error else editProblem?.text() ?: "未能保存修改，请重试",
            errorProblem = if (editError == null) errorProblem else editProblem,
            historyLoading = false,
            historyError = if (before == null) null else historyError,
            olderHistoryError = null,
            loadingMore = false,
            // A known history start stays known when newer replies arrive.
            hasMoreHistory = (before != null || replace || hasMoreHistory) &&
                page.any { it.listKey !in knownKeys } && page.any { !it.id.isNullOrBlank() },
        ),
        restoredDraft = restore != null,
    )
}
