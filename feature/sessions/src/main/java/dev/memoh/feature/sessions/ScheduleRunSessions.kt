package dev.memoh.feature.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.memoh.core.model.errorMessage
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ScheduleRunSessions(id: String?, state: ScheduleRunsState?, onLoad: (Boolean) -> Unit, onSession: (String) -> Unit) {
    if (id.isNullOrBlank()) return
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(id, expanded, lifecycle) {
        if (expanded) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { load(false); delay(10_000) }
        }
    }
    TextButton({ expanded = !expanded }) {
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        Spacer(Modifier.width(6.dp))
        Text(if (expanded) "收起运行会话" else "运行会话")
    }
    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            state?.items.orEmpty().forEachIndexed { index, run ->
                key(run.logId) {
                    val leading: @Composable () -> Unit = {
                        Icon(if (run.status == "running") Icons.Filled.HourglassTop else if (run.status == "error") Icons.Filled.ErrorOutline else Icons.Filled.CheckCircleOutline,
                            null, tint = if (run.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    val supporting: @Composable () -> Unit = {
                        Column {
                            Text("${scheduleLogStatus(run.status)} · ${runTime(run.startedAt)}", style = MaterialTheme.typography.bodySmall)
                            run.errorCode?.takeIf(String::isNotBlank)?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    val headline: @Composable () -> Unit = {
                        Text(run.title.ifBlank { if (run.sessionId.isBlank()) "尚未创建执行会话" else "未命名会话" }, maxLines = 2)
                    }
                    if (run.sessionId.isNotBlank()) SegmentedListItem(onClick = { onSession(run.sessionId) },
                        shapes = ListItemDefaults.segmentedShapes(index, state?.items.orEmpty().size),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        leadingContent = leading, supportingContent = supporting,
                        trailingContent = { Icon(Icons.Filled.ChevronRight, "打开执行会话") }, content = headline)
                    else ListItem(headlineContent = headline, leadingContent = leading, supportingContent = supporting,
                        modifier = Modifier.clip(MaterialTheme.shapes.medium),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
                }
            }
            if (state?.loading == true) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
            else if (state?.loaded == true && state.items.isEmpty() && state.error == null) Text("还没有运行会话", style = MaterialTheme.typography.bodySmall)
            state?.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton({ onLoad(false) }) { Text("重试") }
            }
            if (state?.nextCursor != null && !state.loading) TextButton({ onLoad(true) }) { Text("加载更早的运行") }
        }
    }
}

private fun runTime(value: String?): String = value?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrDefault(it)
}.orEmpty()
