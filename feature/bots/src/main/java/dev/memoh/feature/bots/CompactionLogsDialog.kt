package dev.memoh.feature.bots

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.memoh.core.designsystem.component.MemohFormDialog
import dev.memoh.core.designsystem.component.MemohListSkeleton
import dev.memoh.core.network.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun CompactionLogsDialog(botId: String, load: suspend (Int) -> JsonObject, onDismiss: () -> Unit) {
    var offset by remember(botId) { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var response by remember(botId, offset) { mutableStateOf<JsonObject?>(null) }
    var error by remember(botId, offset) { mutableStateOf<String?>(null) }
    var loading by remember(botId, offset) { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(botId, offset, refresh, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (response == null) loading = true
                try { response = load(offset); error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.userMessage("压缩记录加载失败") }
                finally { loading = false }
                delay(15_000)
            }
        }
    }
    val logs = response?.get("items").objects()
    val total = (response?.get("total_count") as? JsonPrimitive)?.longOrNull ?: 0
    MemohFormDialog("上下文压缩", onDismiss, confirmButton = { TextButton(onDismiss) { Text("关闭") } }, dismissButton = {
        TextButton({ loading = true; refresh++ }, enabled = !loading) { Text("刷新") }
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("按当前模型的可用预算自动压缩，任务会接续执行。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(WavyProgressIndicatorDefaults.LinearContainerHeight)) { if (loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth()) }
            if (loading && response == null) MemohListSkeleton(leading = true, detailed = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                itemsIndexed(logs, key = { _, log -> log.text("id") }) { index, log ->
                    val status = when (log.text("status")) { "ok" -> "已完成"; "pending" -> "压缩中"; else -> "压缩失败" }
                    val time = runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(log.text("started_at"))) }
                        .getOrDefault(log.text("started_at"))
                    ManagementRow(status, "$time · ${log.text("message_count")} 条消息", Icons.Filled.UnfoldLess, index, logs.size,
                        onClick = { selectedId = log.text("id") })
                }
                if (!loading && response != null && logs.isEmpty()) item { Text("暂无压缩记录", Modifier.padding(16.dp)) }
            }
            if (total > 0 || offset > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ offset = (offset - 20).coerceAtLeast(0) }, enabled = !loading && offset > 0) { Text("上一页") }
                Text(if (response == null) "第 ${offset / 20 + 1} 页" else "${offset + 1}–${minOf((offset + logs.size).toLong(), total)} / $total",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium)
                TextButton({ offset += 20 }, enabled = !loading && offset + logs.size < total) { Text("下一页") }
            }
        }
    })
    logs.firstOrNull { it.text("id") == selectedId }?.let { log ->
        val text = log.text("summary").ifBlank { log.text("error_message").ifBlank { "压缩中，完成后可查看摘要。" } }
        val chunks = remember(text) { buildList {
            var start = 0
            while (start < text.length) {
                var end = minOf(start + 1200, text.length)
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                add(text.substring(start, end)); start = end
            }
        } }
        MemohFormDialog("压缩摘要", { selectedId = null }, confirmButton = { TextButton({ selectedId = null }) { Text("返回记录") } }, dismissButton = {}, text = {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(chunks) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        })
    }
}
