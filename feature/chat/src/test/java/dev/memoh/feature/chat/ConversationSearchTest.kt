package dev.memoh.feature.chat

import dev.memoh.core.model.UIAttachment
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UITurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {
    @Test fun findsTextAndAttachmentNamesWithoutSearchingUnrelatedTurns() {
        val turns = listOf(
            UITurn("a", id = "one", role = "user", text = "请检查服务日志"),
            UITurn("b", id = "two", role = "assistant", messages = listOf(
                UIMessage(0, "text", "报告已经完成"),
                UIMessage(1, "attachments", attachments = listOf(UIAttachment(name = "report.pdf"))),
            )),
        )
        assertEquals("user:one", searchConversation(turns, "服务").single().turnKey)
        val file = searchConversation(turns, "REPORT.PDF").single()
        assertEquals("assistant:two", file.turnKey)
        assertEquals(1, file.messageIndex)
        assertTrue(file.snippet.contains("report.pdf"))
        assertTrue(searchConversation(turns, "missing").isEmpty())
    }

    @Test fun findsTheActualLazyRowWhenAssistantBlocksShareATurn() {
        val turns = listOf(
            UITurn("a", id = "one", role = "user", text = "问题"),
            UITurn("b", id = "two", role = "assistant", messages = listOf(
                UIMessage(0, "reasoning", "先检查"), UIMessage(1, "tool", name = "read"),
                UIMessage(2, "text", "第一段"), UIMessage(3, "text", "目标答案"),
            )),
            UITurn("c", id = "three", role = "user", text = "另一条"),
        )
        assertEquals(3, conversationHitIndex(turns, searchConversation(turns, "目标答案").single()))
        assertEquals(5, conversationHitIndex(turns, searchConversation(turns, "另一条").single()))
    }

    @Test fun returnsEachMatchingMessageInAnAssistantTurn() {
        val turns = listOf(UITurn("t", role = "assistant", messages = listOf(
            UIMessage(0, "text", "第一条目标"),
            UIMessage(1, "text", "没有命中"),
            UIMessage(2, "text", "第二条目标"),
        )))
        val matches = searchConversation(turns, "目标")
        assertEquals(listOf(0, 2), matches.map { it.messageIndex })
        assertEquals(listOf("第一条目标", "第二条目标"), matches.map { it.snippet })
        assertEquals(listOf(0, 2), matches.map { conversationHitIndex(turns, it) })
    }

    @Test fun excerptKeepsTheMatchWhenTheQueryContainsRepeatedWhitespace() {
        val turn = UITurn("t", role = "user", text = "前".repeat(150) + "目标  内容")
        val hit = searchConversation(listOf(turn), "目标  内容").single()
        assertTrue(hit.snippet.contains("目标 内容"))
    }

    @Test fun cachedMessageWithChangedContentDoesNotMatchTheLiveRow() {
        val cached = UITurn("t", id = "id", role = "assistant", messages = listOf(UIMessage(0, "text", "旧目标")))
        val hit = searchConversation(listOf(cached), "目标").single()
        assertTrue(sameCachedSearchTarget(hit, cached, cached))
        val updated = cached.copy(messages = listOf(UIMessage(0, "text", "新的内容")))
        assertTrue(!sameCachedSearchTarget(hit, cached, updated))
    }
}
