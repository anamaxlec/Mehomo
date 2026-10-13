package dev.memoh.feature.chat

import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UIUserInput
import org.junit.Assert.*
import org.junit.Test

class AssistantRenderItemsTest {
    @Test fun processRunsKeepTextErrorsAndAttachmentsInTheirOriginalOrder() {
        val messages = listOf(
            UIMessage(0, "reasoning", "检查源码"), UIMessage(1, "tool", name = "read", toolCallId = "read-1"),
            UIMessage(2, "text", "发现一个问题"), UIMessage(3, "tool", name = "edit", toolCallId = "edit-1"),
            UIMessage(4, "reasoning", "准备验证"), UIMessage(5, "error", "验证失败"), UIMessage(6, "attachments"),
        )
        val items = assistantRenderItems("turn-1", messages)
        assertEquals(listOf(2, 1, 2, 1, 1), items.map { it.messages.size })
        assertEquals(messages, items.flatMap { it.messages })
        assertEquals(listOf(true, false, true, false, false), items.map { it.isProcess })
    }

    @Test fun settledQuestionsBreakOutOfTheProcessWithoutHidingAdjacentSteps() {
        val question = UIMessage(2, "tool", name = "ask_user", userInput = UIUserInput("question", status = "submitted"))
        val items = assistantRenderItems("turn", listOf(UIMessage(1, "reasoning", "思考"), question,
            UIMessage(3, "tool", name = "exec")))
        assertEquals(3, items.size)
        assertFalse(items[1].isProcess)
        assertEquals(question, items[1].messages.single())
    }

    @Test fun processIdentitySurvivesStreamingGrowthAndRestRenumbering() {
        val first = UIMessage(3, "reasoning", "思考")
        val tool = UIMessage(5, "tool", name = "read", toolCallId = "stable-call")
        val live = assistantRenderItems("turn", listOf(first, UIMessage(4, "status"), tool))
        val history = assistantRenderItems("turn", listOf(first.copy(id = 0), tool.copy(id = 1)))
        assertEquals(assistantRenderItems("turn", listOf(first)).single().key, live.single().key)
        assertEquals(live.single().key, history.single().key)
        assertEquals(assistantRenderItems("turn", listOf(tool)).single().key,
            assistantRenderItems("turn", listOf(tool.copy(id = 0))).single().key)
    }
}
