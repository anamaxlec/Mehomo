package dev.memoh.feature.bots

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohMenuGroup
import dev.memoh.core.designsystem.component.MemohMenuRow
import dev.memoh.core.designsystem.component.MemohPopupMenu

/** Compact channel card: equal insets, centered controls, and readable built-in channels. */
@Composable
internal fun ChannelRow(type: String, title: String, status: String, enabled: Boolean,
    onConfigure: (() -> Unit)?, checked: Boolean?, onChecked: (Boolean) -> Unit, onDisconnect: (() -> Unit)?) {
    var menuOpen by remember(type) { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val resource = channelIcon(type)
                if (resource != null) Image(painterResource(resource), null, Modifier.size(28.dp))
                else Icon(if (type == "web") Icons.Filled.Language else Icons.Filled.Forum, null,
                    Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (checked != null && onConfigure != null) Switch(checked, onChecked, enabled = enabled)
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (onDisconnect != null) {
                        IconButton({ menuOpen = true }, enabled = enabled) { Icon(Icons.Filled.MoreVert, "${title}操作", Modifier.size(20.dp)) }
                        MemohPopupMenu(menuOpen, { menuOpen = false }, Alignment.TopEnd) { MemohMenuGroup {
                            MemohMenuRow("断开渠道", Icons.Filled.LinkOff, { menuOpen = false; onDisconnect() }, index = 0, count = 1)
                        } }
                    } else Icon(if (onConfigure != null) Icons.Filled.ChevronRight else if (checked == true) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                        null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val modifier = Modifier.fillMaxWidth().testTag("channel-row-$type")
    if (onConfigure == null) Surface(modifier, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest, content = content)
    else Surface(onClick = onConfigure, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest, content = content)
}

private fun channelIcon(type: String): Int? = when (type.lowercase()) {
    "dingtalk" -> R.drawable.channel_dingtalk
    "discord" -> R.drawable.channel_discord
    "feishu" -> R.drawable.channel_feishu
    "line" -> R.drawable.channel_line
    "qq" -> R.drawable.channel_qq
    "slack" -> R.drawable.channel_slack
    "telegram" -> R.drawable.channel_telegram
    "wechat", "weixin" -> R.drawable.channel_wechat
    else -> null
}
