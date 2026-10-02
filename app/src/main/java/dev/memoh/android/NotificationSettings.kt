package dev.memoh.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import dev.memoh.android.notifications.MonitorStatus
import dev.memoh.core.designsystem.component.ListIconTone
import dev.memoh.core.designsystem.component.MemohListIcon
import dev.memoh.feature.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NotificationSettings(settings: SettingsViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val enabled by settings.backgroundMonitor.collectAsState()
    val live by settings.liveUpdates.collectAsState()
    val done by settings.notifyReplyDone.collectAsState()
    val decisions by settings.notifyDecisions.collectAsState()
    val status by MonitorStatus.state.collectAsState()
    var permissionRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionRevision++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val manager = remember(context) { NotificationManagerCompat.from(context) }
    val allowed = remember(permissionRevision) { manager.areNotificationsEnabled() }
    val promoted = remember(permissionRevision) { Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRevision++
        settings.setBackgroundMonitor(granted)
    }
    Column(Modifier.fillMaxWidth()) {
        Text("后台通知", Modifier.padding(start = 12.dp, bottom = 10.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            NotificationSwitch("后台监控当前 Bot", enabled, Icons.Filled.NotificationsActive,
                subtitle = "${if (!allowed) "通知权限已关闭，后台监控未运行" else status.description}\n持续连接会增加耗电；可从监控通知停止。",
                index = 0) { value ->
                if (value && !allowed && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else settings.setBackgroundMonitor(value)
            }
            NotificationSwitch("本轮结束提醒", done, Icons.Filled.TaskAlt,
                subtitle = "任务完成或失败时提醒", index = 1, onChange = settings::setNotifyReplyDone)
            NotificationSwitch("审批与补充输入提醒", decisions, Icons.Filled.RateReview,
                subtitle = "需要你处理时提醒", index = 2, tone = ListIconTone.Tertiary,
                onChange = settings::setNotifyDecisions)
            NotificationSwitch("任务 Live Updates", live, Icons.Filled.AutoAwesome,
                subtitle = when {
                    !live -> "已关闭，运行进度使用普通任务通知显示"
                    Build.VERSION.SDK_INT < 36 -> "此系统通过普通任务通知显示进度"
                    !promoted -> "系统尚未允许置顶，使用普通任务通知显示进度"
                    else -> "运行时可置顶显示任务进度"
                }, index = 3, tone = ListIconTone.Tertiary, onChange = settings::setLiveUpdates)
            NotificationRow("系统通知设置", "管理通知权限、声音与置顶显示", Icons.Filled.Settings,
                index = 4, tone = ListIconTone.Secondary,
                onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
                trailing = { Icon(Icons.Filled.ChevronRight, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotificationSwitch(title: String, checked: Boolean, icon: ImageVector, subtitle: String,
    index: Int, tone: ListIconTone = ListIconTone.Primary, onChange: (Boolean) -> Unit) {
    NotificationRow(title, subtitle, icon, index, tone, { onChange(!checked) },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        }) {
        Switch(checked = checked, onCheckedChange = null,
            thumbContent = if (checked) {{ Icon(Icons.Filled.Check, null, Modifier.size(SwitchDefaults.IconSize)) }} else null)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotificationRow(title: String, subtitle: String, icon: ImageVector, index: Int,
    tone: ListIconTone, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    SegmentedListItem(onClick = onClick, modifier = modifier.fillMaxWidth(),
        shapes = ListItemDefaults.segmentedShapes(index, 5),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        leadingContent = { MemohListIcon(icon, tone) }, trailingContent = trailing,
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium) },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
