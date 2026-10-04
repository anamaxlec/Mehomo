package dev.memoh.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.designsystem.component.MemohFormDialog
import dev.memoh.core.model.BotSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ScheduleFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) },
        leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) } } else null,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer))
}

@Composable
internal fun ScheduleSummary(schedule: BotSchedule, timezone: String?) {
    val maxCalls = schedule.maxCalls
    val next by produceState<ZonedDateTime?>(null, schedule.pattern, schedule.enabled, schedule.currentCalls, timezone) {
        value = if (schedule.enabled == true && (maxCalls == null || (schedule.currentCalls ?: 0) < maxCalls))
            withContext(Dispatchers.Default) { timezone?.let { nextPreset(schedule.pattern.orEmpty(), it) } } else null
    }
    Text(buildString {
        append("已运行 ${schedule.currentCalls ?: 0} 次${schedule.maxCalls?.let { " / $it" }.orEmpty()}")
        append(if (schedule.runTarget == "existing_session") " · 使用已有会话" else " · 每次新建会话")
        if (maxCalls != null && (schedule.currentCalls ?: 0) >= maxCalls) append("\n已达到执行次数")
        else next?.let { append("\n下次执行 · ${it.format(DateTimeFormatter.ofPattern("MM-dd HH:mm z"))}") }
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun scheduleLogStatus(status: String?): String = when (status) {
    "running", "started" -> "执行中"
    "success", "succeeded", "completed" -> "已完成"
    "error", "failed" -> "执行失败"
    "cancelled", "canceled", "aborted" -> "已取消"
    "timeout", "timed_out" -> "执行超时"
    else -> status ?: "执行记录"
}

private fun logTime(value: String?): String = value?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) }.getOrDefault(it)
}.orEmpty()

@Composable
internal fun ScheduleLogsDialog(state: BotFeatureState, onRefresh: () -> Unit, onMore: () -> Unit,
    onDismiss: () -> Unit, onSession: (String) -> Unit) {
    val logs = state.logs?.items.orEmpty()
    MemohFormDialog(state.logSchedule?.name ?: "执行记录", onDismiss,
        confirmButton = { MemohActionButton("关闭", Icons.Filled.Close, onDismiss) },
        dismissButton = { MemohActionButton("刷新", Icons.Filled.Refresh, onRefresh, enabled = !state.logsLoading) }, text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("执行记录 · ${state.logs?.totalCount ?: logs.size}", style = MaterialTheme.typography.labelLarge) }
                state.logsError?.let { error -> item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    MemohActionButton("重试", Icons.Filled.Refresh, if (logs.isEmpty()) onRefresh else onMore)
                } }
                if (logs.isEmpty() && !state.logsLoading && state.logsError == null) item { Text("暂无执行记录") }
                itemsIndexed(logs, key = { index, log -> log.id ?: index }) { _, log ->
                    var expanded by remember(log.id) { mutableStateOf(false) }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(scheduleLogStatus(log.status), style = MaterialTheme.typography.titleSmall,
                                color = if (!log.errorMessage.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Text(logTime(log.startedAt) + log.completedAt?.let { " → ${logTime(it)}" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            val result = listOfNotNull(log.errorMessage?.takeIf(String::isNotBlank), log.resultText?.takeIf(String::isNotBlank)).joinToString("\n")
                            if (result.isNotEmpty()) {
                                SelectionContainer { Text(result, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                                TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开结果") }
                            }
                            log.sessionId?.takeIf(String::isNotBlank)?.let { id -> MemohActionButton("查看执行会话", Icons.AutoMirrored.Filled.Chat, { onSession(id) }) }
                        }
                    }
                }
                if (state.logsLoading) item { LoadingIndicator(Modifier.size(40.dp)) }
                else if (logs.isNotEmpty() && logs.size < (state.logs?.totalCount ?: 0) && state.logsError == null) item {
                    MemohActionButton("加载更多记录", Icons.Filled.ExpandMore, onMore)
                }
            }
        })
}
