package dev.memoh.feature.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.model.errorMessage
import dev.memoh.feature.chat.ChatUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ContextInspectorSheet(state: ChatUiState, onLoad: (Boolean) -> Unit, onDismiss: () -> Unit,
    onRefreshSession: () -> Unit = {}, onCompact: () -> Unit = {}) {
    val page = state.contextLifecycle
    val turns = remember(page) { page?.turns.orEmpty().sortedBy { it.createdAt } }
    var selectedKey by rememberSaveable(state.session?.id) { mutableStateOf("") }
    val selected = turns.firstOrNull { it.runId.ifBlank { it.assistantMessageId.orEmpty() } == selectedKey } ?: turns.lastOrNull()
    val strip = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) strip.scrollToItem(turns.lastIndex) }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp), contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("上下文检查器", style = MaterialTheme.typography.titleLarge)
                    TextButton({ onLoad(false); onRefreshSession() }, enabled = !state.contextLifecycleLoading) { Text("刷新") }
                }
                Text("显示 ${turns.size} 轮记录 · 统计仅覆盖当前返回的记录", style = MaterialTheme.typography.bodySmall)
                if (page?.legacySource == true) Text("这些记录来自旧版会话历史", style = MaterialTheme.typography.bodySmall)
            }
            if (state.contextLifecycleLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.contextLifecycleError?.let { error -> item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton({ onLoad(false) }) { Text("重试") }
            } }
            if (turns.isEmpty() && !state.contextLifecycleLoading && state.contextLifecycleError == null) item { Text("此会话尚无上下文记录") }
            if (turns.isNotEmpty()) item {
                LazyRow(state = strip, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(turns) { index, turn ->
                        FilterChip(turn == selected, { selectedKey = turn.runId.ifBlank { turn.assistantMessageId.orEmpty() } },
                            label = { Column { Text("第 ${index + 1} 轮"); Text(formatTokens(turn.snapshot.usage.tokens), style = MaterialTheme.typography.labelSmall) } })
                    }
                }
            }
            selected?.let { turn ->
                val snapshot = turn.snapshot
                val usage = snapshot.usage
                item {
                    Text(contextTime(turn.createdAt), style = MaterialTheme.typography.labelMedium)
                    snapshot.model?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("${formatTokens(usage.tokens)} / ${usage.window()?.let(::formatTokens) ?: "窗口未知"} tokens · 估算", style = MaterialTheme.typography.titleMedium)
                    if (!turn.errorCode.isNullOrBlank()) Text(errorMessage(turn.errorCode), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                item { ContextWaffle(usage) }
                item { ContextLegend(usage, showShares = true) }
                item {
                    InspectorRow("保留片段", snapshot.selection.selected.toString())
                    InspectorRow("未纳入片段", snapshot.selection.dropped.toString())
                    InspectorRow("裁剪片段", snapshot.selection.trimmed.toString())
                    snapshot.selection.dropReasonTokens?.let { InspectorRow("未纳入 tokens", formatTokens(it.values.sum())) }
                    snapshot.selection.dropReasons.filterValues { it > 0 }.forEach { (reason, count) ->
                        InspectorRow(when (reason) {
                            "history_budget" -> "历史预算"
                            "system_budget" -> "系统预算"
                            "protected_context_overflow" -> "必要内容超限"
                            "summary_coverage" -> "已由摘要覆盖"
                            "budget" -> "上下文预算"
                            else -> "其他原因"
                        }, count.toString())
                    }
                    usage.budgetPlan?.let { InspectorRow("输出预留", formatTokens(it.outputReserve)) }
                    InspectorRow("本轮缓存读取", formatTokens(snapshot.cacheReadTokens))
                    InspectorRow("本轮缓存写入", formatTokens(snapshot.cacheWriteTokens))
                }
            }
            page?.let { data -> item {
                HorizontalDivider()
                InspectorRow("当前记录缓存读取合计", formatTokens(data.aggregates.totalCacheReadTokens))
                InspectorRow("当前记录缓存写入合计", formatTokens(data.aggregates.totalCacheWriteTokens))
                Text("外部 Agent 可能不提供模型缓存用量，缺失数据不代表实际未使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (data.hasMore && data.limit < 200) TextButton({ onLoad(true) }, enabled = !state.contextLifecycleLoading) { Text("加载至最近 200 轮") }
                else if (data.hasMore || data.legacyHistoryMayExist) Text("仍可能有更早的历史，以上统计不代表整个会话。", style = MaterialTheme.typography.bodySmall)
            } }
            state.sessionInfo?.let { info -> item {
                HorizontalDivider()
                Text("当前会话", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                InspectorRow("消息", info.messageCount.toString())
                val usage = info.contextUsage
                if (usage.estimatedTokens != null && usage.usedTokens > 0) InspectorRow("模型实际输入", formatTokens(usage.usedTokens))
                usage.budgetPlan?.outputReserve?.takeIf { it > 0 }?.let { InspectorRow("输出预留", formatTokens(it)) }
                usage.autoCompactTokens?.let { InspectorRow("自动压缩阈值", formatTokens(it)) }
                InspectorRow("缓存命中", String.format(java.util.Locale.ROOT, "%.1f%%", info.cacheStats.cacheHitRate))
                InspectorRow("缓存读取", formatTokens(info.cacheStats.cacheReadTokens))
                if (info.skills.isNotEmpty()) InspectorRow("使用的技能", info.skills.joinToString("、"))
                if (usage.compaction != null) MemohActionButton(if (state.busy) "处理中…" else "压缩上下文", Icons.Filled.Compress,
                    onClick = { onDismiss(); onCompact() }, enabled = usage.tokens > 0 && !state.busy && !state.isRunning, modifier = Modifier.fillMaxWidth())
            } }
        }
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}

private fun contextTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
}.getOrDefault(value)
