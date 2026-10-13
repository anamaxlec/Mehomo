package dev.memoh.feature.chat.components

import dev.memoh.core.model.ContextKindUsage
import dev.memoh.core.model.ContextToolUsage
import dev.memoh.core.model.ContextUsage
import org.junit.Assert.*
import org.junit.Test

class ContextCompositionTest {
    @Test fun runtimeKindsAndToolSchemasFoldIntoTheOfficialOrderedGroups() {
        val kinds = listOf("conversation_event", "memory_recall", "conversation_summary", "runtime_context",
            "hook_context", "unknown_future_kind", "skills_catalog", "tool_usage", "workspace_instruction",
            "system_prompt", "system_policy", "bot_identity", "platform_identity")
        val groups = contextGroups(ContextUsage(breakdown = kinds.map { ContextKindUsage(it, tokenEstimate = 10) },
            toolDefs = listOf(ContextToolUsage("mcp", tokenEstimate = 100))))
        assertEquals(listOf(ContextGroup(ContextGroupId.Instructions, 50), ContextGroup(ContextGroupId.Tools, 120),
            ContextGroup(ContextGroupId.Conversation, 60)), groups)
    }

    @Test fun tinyConversationShareGetsACellInsteadOfDisappearing() {
        val groups = listOf(ContextGroup(ContextGroupId.Instructions, 1000), ContextGroup(ContextGroupId.Tools, 8999),
            ContextGroup(ContextGroupId.Conversation, 1))
        val cells = contextWaffleCells(10.0, groups)
        assertEquals(100, cells.size)
        assertEquals(10, cells.count { it != ContextCell.Empty })
        assertEquals(1, cells.count { it == ContextCell.Conversation })
        assertEquals(ContextCell.Instructions, cells.first())
        assertEquals(ContextCell.Conversation, cells[9])
    }

    @Test fun fewerCellsThanGroupsUsesTheLargestGroupsWithoutInflatingUsage() {
        val groups = listOf(ContextGroup(ContextGroupId.Instructions, 5), ContextGroup(ContextGroupId.Tools, 50),
            ContextGroup(ContextGroupId.Conversation, 10))
        assertEquals(listOf(ContextCell.Tools) + List(99) { ContextCell.Empty }, contextWaffleCells(.1, groups))
        assertEquals(listOf(ContextCell.Tools, ContextCell.Conversation) + List(98) { ContextCell.Empty }, contextWaffleCells(2.0, groups))
    }

    @Test fun spareCellsUseLargestRemaindersAndKeepTheLegendOrder() {
        val cells = contextWaffleCells(50.0, listOf(ContextGroup(ContextGroupId.Instructions, 2),
            ContextGroup(ContextGroupId.Tools, 3), ContextGroup(ContextGroupId.Conversation, 5)))
        assertEquals(List(10) { ContextCell.Instructions } + List(15) { ContextCell.Tools } +
            List(25) { ContextCell.Conversation } + List(50) { ContextCell.Empty }, cells)
    }

    @Test fun zeroFullAndLegacyUsageRemainBoundedToOneHundredCells() {
        assertEquals(List(100) { ContextCell.Empty }, contextWaffleCells(0.0, emptyList()))
        assertEquals(List(100) { ContextCell.Empty }, contextWaffleCells(-10.0, emptyList()))
        assertEquals(List(100) { ContextCell.Used }, contextWaffleCells(150.0, emptyList()))
        assertEquals(List(9) { ContextCell.Used } + List(91) { ContextCell.Empty }, contextWaffleCells(8.75, emptyList()))
    }
}
