package dev.memoh.core.model

data class ConversationMatch(val turnKey: String, val messageIndex: Int?, val label: String, val snippet: String)

/** Search locally available message text and attachment names; no remote history is implied. */
fun searchTurns(turns: List<UITurn>, query: String): List<ConversationMatch> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()
    return buildList {
        turns.forEach { turn ->
            val label = if (turn.isUser) "我" else if (turn.isAssistant) "助手" else "消息"
            fun addMatch(messageIndex: Int?, sources: List<String>) {
                val source = sources.firstOrNull { it.contains(term, ignoreCase = true) } ?: return
                val match = source.indexOf(term, ignoreCase = true)
                val from = (match - 35).coerceAtLeast(0)
                val to = (match + term.length + 65).coerceAtMost(source.length)
                val excerpt = source.substring(from, to).replace(Regex("\\s+"), " ").trim()
                add(ConversationMatch(turn.listKey, messageIndex, label,
                    (if (from > 0) "…" else "") + excerpt + if (to < source.length) "…" else ""))
            }
            addMatch(null, listOfNotNull(turn.text) + turn.attachments.orEmpty().map {
                listOfNotNull(it.name, it.path).joinToString(" ")
            })
            turn.safeMessages.forEachIndexed { index, message ->
                addMatch(index, listOf(message.content) + message.attachments.orEmpty().map {
                    listOfNotNull(it.name, it.path).joinToString(" ")
                })
            }
        }
    }
}
