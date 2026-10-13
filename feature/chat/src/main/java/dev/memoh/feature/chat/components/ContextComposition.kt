package dev.memoh.feature.chat.components

import dev.memoh.core.model.ContextUsage
import kotlin.math.floor
import kotlin.math.roundToInt

internal enum class ContextCell { Instructions, Tools, Conversation, Used, Empty }

internal enum class ContextGroupId(val label: String, val cell: ContextCell) {
    Instructions("设定", ContextCell.Instructions),
    Tools("工具", ContextCell.Tools),
    Conversation("对话", ContextCell.Conversation),
}

internal data class ContextGroup(val id: ContextGroupId, val tokens: Long)

/** Memoh's context-groups.ts folds the eight runtime categories into these three groups. */
internal fun contextGroups(usage: ContextUsage): List<ContextGroup> {
    val tokens = mutableMapOf<ContextGroupId, Long>()
    usage.breakdown.forEach { entry ->
        val group = when (entry.kind) {
            "system_prompt", "system_policy", "bot_identity", "platform_identity", "workspace_instruction" -> ContextGroupId.Instructions
            "tool_usage", "skills_catalog" -> ContextGroupId.Tools
            else -> ContextGroupId.Conversation
        }
        tokens[group] = (tokens[group] ?: 0) + entry.tokenEstimate.coerceAtLeast(0)
    }
    tokens[ContextGroupId.Tools] = (tokens[ContextGroupId.Tools] ?: 0) +
        usage.toolDefs.sumOf { it.tokenEstimate.coerceAtLeast(0) }
    return ContextGroupId.entries.mapNotNull { id ->
        tokens[id]?.takeIf { it > 0 }?.let { ContextGroup(id, it) }
    }
}

/** Same largest-remainder allocation as official context-waffle.vue, including tiny shares. */
internal fun contextWaffleCells(percent: Double, groups: List<ContextGroup>): List<ContextCell> {
    val raw = if (percent.isNaN()) 0.0 else percent.coerceIn(0.0, 100.0)
    val used = if (raw > 0) raw.roundToInt().coerceAtLeast(1) else 0
    val largest = groups.sortedByDescending { it.tokens }.take(used).map { it.id }.toSet()
    val present = groups.filter { it.id in largest }
    val total = present.sumOf { it.tokens.toDouble() }
    return buildList {
        if (present.isEmpty() || total <= 0) {
            repeat(used) { add(ContextCell.Used) }
        } else {
            val spare = used - present.size
            val shares = present.map { it.tokens / total * spare }
            val counts = shares.map { 1 + floor(it).toInt() }.toMutableList()
            val order = shares.indices.sortedByDescending { shares[it] - floor(shares[it]) }
            order.take(used - counts.sum()).forEach { counts[it]++ }
            present.forEachIndexed { index, group -> repeat(counts[index]) { add(group.id.cell) } }
        }
        while (size < 100) add(ContextCell.Empty)
    }
}
