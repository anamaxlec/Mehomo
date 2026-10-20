package dev.memoh.feature.chat

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
