package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.CodexUsage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CodexUsagePanel(usage: CodexUsage?, loading: Boolean, error: String?, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ChatGPT 账号额度", style = MaterialTheme.typography.titleMedium)
            TextButton(onRefresh, enabled = !loading) { Text("刷新") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (usage?.limitReached == true) Text("账号额度已用尽，请等待重置。", color = MaterialTheme.colorScheme.error)
        error?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        usage?.windows?.forEach { window ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(window.label, style = MaterialTheme.typography.bodyMedium)
                Text("${String.format(Locale.ROOT, "%.0f", window.usedPercent)}% 已用", style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(progress = { (window.usedPercent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                color = if (window.usedPercent >= 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(window.resetsAt?.let { time -> "重置时间 · " + runCatching {
                Instant.parse(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            }.getOrDefault(time) } ?: "未提供重置时间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (usage != null && usage.windows.isEmpty()) Text("服务端未提供额度窗口。", style = MaterialTheme.typography.bodySmall)
        Text("额度由账号共享；此处与按 token 统计的用量分开显示。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
