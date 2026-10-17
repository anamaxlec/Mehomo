package dev.memoh.feature.chat.components

import org.junit.Assert.*
import org.junit.Test

class ReasoningTextTest {
    @Test fun longThoughtsKeepEveryCharacterIncludingWhitespaceAndEmoji() {
        val content = ("一步一步检查运行状态与工具输出。\n\n" + "x".repeat(1_175) + "🙂\n").repeat(200)
        val chunks = reasoningTextChunks(content)
        assertEquals(content, chunks.joinToString(""))
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= 1_200 })
        assertTrue(chunks.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }

    @Test fun aSurrogatePairAtTheChunkBoundaryStaysTogether() {
        val content = "x".repeat(1_199) + "🙂" + "y".repeat(1_201)
        val chunks = reasoningTextChunks(content)
        assertEquals(content, chunks.joinToString(""))
        assertEquals(1_199, chunks.first().length)
        assertTrue(chunks[1].startsWith("🙂"))
    }
}
