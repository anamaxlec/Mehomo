package dev.memoh.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.designsystem.component.MemohFormDialog
import dev.memoh.core.markdown.MemohMarkdown
import android.content.ClipData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun AppUpdateState.description(): String = when (status) {
    UpdateStatus.Idle -> "从 GitHub 发行版检查更新"
    UpdateStatus.Checking -> "正在检查 GitHub 发行版…"
    UpdateStatus.Current -> if (release == null) "尚无正式发行版" else "已是最新正式版"
    UpdateStatus.Available -> "发现新版本 ${release?.version.orEmpty()}"
    UpdateStatus.Error -> error ?: "检查更新失败，请重试"
}

@Composable
internal fun AppUpdateSettings(state: AppUpdateState, automatic: Boolean, onAutomatic: (Boolean) -> Unit,
    onCheck: () -> Unit, onRelease: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val crashReport by produceState<String?>(null, context) {
        value = withContext(Dispatchers.IO) { runCatching { LocalCrashReports.read(context) }.getOrNull() }
    }
    var copied by remember { mutableStateOf(false) }
    MemohFormDialog("版本与更新", onDismiss,
        confirmButton = { MemohActionButton(if (state.status == UpdateStatus.Available) "查看更新" else "检查更新", Icons.Filled.Refresh,
            if (state.status == UpdateStatus.Available) onRelease else onCheck, enabled = state.status != UpdateStatus.Checking, primary = true) },
        dismissButton = { TextButton(onDismiss) { Text("关闭") } }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Mehomo ${state.currentVersion}", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.status == UpdateStatus.Checking) LoadingIndicator(Modifier.size(32.dp))
                    Text(state.description(), color = if (state.status == UpdateStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("自动检查更新", style = MaterialTheme.typography.titleSmall)
                        Text("打开应用时检查，每天一次；发现新正式版时提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(automatic, onAutomatic)
                }
                if (crashReport != null) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("崩溃记录", style = MaterialTheme.typography.titleSmall)
                        Text("仅保存在本机，包含应用版本、机型、系统和异常调用栈。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MemohActionButton(if (copied) "崩溃日志已复制" else "复制崩溃日志", Icons.Filled.ContentCopy, {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Mehomo 崩溃日志", crashReport)))
                                copied = true
                            }
                        })
                    }
                }
            }
        })
}

@Composable
internal fun AppUpdateReminder(state: AppUpdateState, onDismiss: () -> Unit) {
    val release = state.release ?: return
    if (!state.showReminder) return
    val uri = LocalUriHandler.current
    var openError by remember(release.version) { mutableStateOf<String?>(null) }
    MemohFormDialog("发现新版本 ${release.version}", onDismiss,
        confirmButton = { MemohActionButton(if (release.apkUrl != null) "下载正式版" else "查看发行版", Icons.Filled.Download, {
            try { uri.openUri(release.apkUrl ?: release.pageUrl); onDismiss() }
            catch (_: Exception) { openError = "无法打开下载链接，请重试" }
        }, primary = true) },
        dismissButton = { TextButton(onDismiss) { Text("稍后") } }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本 ${state.currentVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (release.notes.isNotBlank()) MemohMarkdown.Markdown(release.notes) else Text(release.name)
                release.apkSize?.let { Text("正式版 APK · %.1f MB".format(it / 1_048_576.0), style = MaterialTheme.typography.bodySmall) }
                openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        })
}
