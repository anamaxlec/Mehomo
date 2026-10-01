package dev.memoh.feature.chat.components

import dev.memoh.core.designsystem.component.MemohActionButton
import androidx.compose.material.icons.filled.Compress

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohPopupMenu
import dev.memoh.feature.chat.ChatUiState
import java.util.Locale

@Composable
fun SessionContextIndicator(state: ChatUiState, onRefresh: () -> Unit, onCompact: () -> Unit) {
    var open by remember(state.session?.id) { mutableStateOf(false) }
    val info = state.sessionInfo
    val usage = info?.contextUsage
    val fraction = usage?.fraction(state.modelContextWindow)
    val window = usage?.window(state.modelContextWindow) ?: state.modelContextWindow
    val colors = MaterialTheme.colorScheme
    val tint = when {
        fraction != null && fraction >= .9f -> colors.error
        fraction != null && fraction >= .7f -> colors.tertiary
        else -> colors.primary
    }
    val progress by animateFloatAsState(fraction?.coerceIn(0f, 1f) ?: 0f,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "contextUsage")
    Box {
        IconButton(onClick = { open = !open; if (open) onRefresh() },
            modifier = Modifier.size(40.dp).semantics {
                contentDescription = if (fraction == null) "上下文用量，点击查看详情"
                    else "上下文已用 ${formatPercent(fraction)}%，点击查看详情"
            }) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(18.dp),
                color = tint, trackColor = colors.outlineVariant, strokeWidth = 2.5.dp, strokeCap = StrokeCap.Round)
        }
        MemohPopupMenu(open, { open = false }, Alignment.BottomEnd, width = 248.dp, maxHeight = 480.dp) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Refresh, "刷新上下文信息", Modifier.size(18.dp))
                    }
                }
                if (info == null && state.sessionInfoLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在获取会话用量…", style = MaterialTheme.typography.bodySmall)
                } else if (info != null) {
                    Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (fraction != null) "${formatPercent(fraction)}% 已使用" else "${formatTokens(usage!!.tokens)} tokens",
                                style = MaterialTheme.typography.titleMedium, color = colors.onSecondaryContainer)
                            Text("${formatTokens(usage!!.tokens)} / ${window?.let(::formatTokens) ?: "窗口未知"} tokens" +
                                if (usage.estimatedTokens != null) " · 估算" else "",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
                            if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(), color = tint, trackColor = colors.surface)
                        }
                    }
                    contextCategories(usage!!).forEach { (label, tokens) -> ContextDetailRow(label, formatTokens(tokens)) }
                    ContextDetailRow("消息", info.messageCount.toString())
                    if (usage.estimatedTokens != null && usage.usedTokens > 0) ContextDetailRow("模型实际输入", formatTokens(usage.usedTokens))
                    usage.budgetPlan?.outputReserve?.takeIf { it > 0 }?.let { ContextDetailRow("输出预留", formatTokens(it)) }
                    usage.autoCompactTokens?.let { ContextDetailRow("自动压缩阈值", formatTokens(it)) }
                    ContextDetailRow("缓存命中", "${String.format(Locale.ROOT, "%.1f", info.cacheStats.cacheHitRate)}%")
                    ContextDetailRow("缓存读取", formatTokens(info.cacheStats.cacheReadTokens))
                    if (info.skills.isNotEmpty()) {
                        Text("使用的技能", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        info.skills.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (usage.compaction != null) MemohActionButton(if (state.busy) "处理中…" else "压缩上下文", Icons.Filled.Compress, onClick = onCompact,
                        enabled = usage.tokens > 0 && !state.busy && !state.isRunning,
                        modifier = Modifier.fillMaxWidth())
                }
                state.sessionInfoError?.let {
                    Text("用量暂不可用：$it", style = MaterialTheme.typography.bodySmall, color = colors.error)
                }
            }
        }
    }
}

@Composable
private fun ContextDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatPercent(fraction: Float) = String.format(Locale.ROOT, "%.1f", fraction * 100)
private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1000 -> String.format(Locale.ROOT, "%.1fK", tokens / 1000.0)
    else -> tokens.toString()
}

private fun contextCategories(usage: dev.memoh.core.model.ContextUsage): List<Pair<String, Long>> {
    val result = linkedMapOf<String, Long>()
    usage.breakdown.forEach { entry ->
        val category = when (entry.kind) {
            "system_prompt", "system_policy", "bot_identity", "platform_identity" -> "系统"
            "workspace_instruction" -> "工作区规则"
            "tool_usage" -> "工具"
            "skills_catalog" -> "技能"
            "memory_recall" -> "记忆"
            "conversation_summary" -> "历史摘要"
            "conversation_event", "current_user_message", "attachment_ref", "native_image", "runtime_context" -> "会话"
            else -> "其他"
        }
        result[category] = (result[category] ?: 0) + entry.tokenEstimate.coerceAtLeast(0)
    }
    result["工具"] = (result["工具"] ?: 0) + usage.toolDefs.sumOf { it.tokenEstimate.coerceAtLeast(0) }
    return result.filterValues { it > 0 }.map { it.key to it.value }
}
