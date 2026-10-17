package dev.memoh.feature.chat.components

/** Preserve every character, prefer line boundaries, and never split a UTF-16 pair. */
internal fun reasoningTextChunks(content: String): List<String> = buildList {
    var start = 0
    while (start < content.length) {
        var end = (start + 1_200).coerceAtMost(content.length)
        if (end < content.length) {
            var newline = end - 1
            while (newline >= start + 600 && content[newline] != '\n') newline--
            if (newline >= start + 600) end = newline + 1
            if (content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) end--
        }
        add(content.substring(start, end))
        start = end
    }
}
