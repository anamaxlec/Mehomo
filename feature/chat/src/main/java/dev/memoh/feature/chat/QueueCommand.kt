package dev.memoh.feature.chat

internal data class QueueCommand(val text: String, val steer: Boolean)

private val queueCommandPattern = Regex("^/(steer|queue)(?:\\s+([\\s\\S]*))?$", RegexOption.IGNORE_CASE)

/** Match the web shortcuts while leaving runtime-owned commands untouched. */
internal fun parseQueueCommand(text: String, runtimeCommands: List<String> = emptyList()): QueueCommand? {
    val match = queueCommandPattern.matchEntire(text.trim()) ?: return null
    val name = match.groupValues[1]
    if (name in runtimeCommands) return null
    return QueueCommand(match.groupValues[2].trim(), name.equals("steer", ignoreCase = true))
}
