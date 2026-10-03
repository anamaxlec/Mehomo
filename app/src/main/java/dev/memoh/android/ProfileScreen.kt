package dev.memoh.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.designsystem.component.*
import dev.memoh.core.model.*
import dev.memoh.feature.settings.SettingsViewModel
import dev.memoh.feature.sessions.BotFeature
import dev.memoh.feature.bots.ManagementPage

@Composable
fun ProfileScreen(
    settings: SettingsViewModel,
    bot: Bot?,
    bots: List<Bot>,
    onSelectBot: (Bot) -> Unit,
    onOpenManagement: (ManagementPage) -> Unit,
    onOpenHistory: () -> Unit,
    onOpen: (BotFeature) -> Unit,
) {
    val session by settings.session.collectAsState()
    val mode by settings.themeMode.collectAsState()
    val accent by settings.accent.collectAsState()
    val floating by settings.floatingSections.collectAsState()
    val teams by settings.cloudTeams.collectAsState()
    var teamsOpen by remember(session.account?.accountId) { mutableStateOf(false) }
    var navigationOpen by remember { mutableStateOf(false) }
    var logoutOpen by remember { mutableStateOf(false) }
    var botOpen by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    var accentOpen by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        topBar = { MemohPageTopBar("我的", scrollBehavior = scrollBehavior) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, maxOf(110.dp, LocalFloatingNavigationPadding.current)), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Filled.AccountCircle, null, Modifier.size(44.dp))
                        Column { Text(session.account?.displayName ?: session.account?.username ?: "Mehomo 用户", style = MaterialTheme.typography.titleMedium)
                            Text(if (session.account?.kind == "cloud") "Memoh Cloud" else "自托管服务", style = MaterialTheme.typography.bodyMedium)
                            Text(session.account?.origin.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                SectionLabel("工作空间")
                val cloud = session.account?.kind == "cloud"
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                if (cloud) SettingsRow("Cloud 团队", teams.teams.firstOrNull { it.team?.teamId == session.account?.teamId }?.team?.name ?: "切换团队工作空间",
                    Icons.Filled.Groups, index = 0, count = 2, tone = ListIconTone.Tertiary,
                    onClick = { teamsOpen = true; settings.loadCloudTeams() }, trailing = Icons.Filled.ArrowDropDown)
                Box {
                    SettingsRow(bot?.displayName ?: bot?.name ?: "选择 Bot",
                        if (session.account?.teamId != null) "已连接团队工作空间" else "当前 Bot 的功能与数据",
                        Icons.Filled.SmartToy, index = if (cloud) 1 else 0, count = if (cloud) 2 else 1,
                        onClick = { botOpen = true }, trailing = Icons.Filled.ArrowDropDown)
                    MemohPopupMenu(botOpen, { botOpen = false }, Alignment.TopStart) {
                        MemohMenuGroup { bots.forEachIndexed { index, item -> MemohMenuRow(item.displayName ?: item.name, Icons.Filled.SmartToy,
                            { onSelectBot(item); botOpen = false }, selected = item.id == bot?.id, selectable = true, index = index, count = bots.size) } }
                    }
                }
                }
            }
            val groups = listOf(
                "Bot 与工作空间" to listOf(ManagementPage.Bots, ManagementPage.Bot, ManagementPage.Agents, ManagementPage.Workspace, ManagementPage.Computers, ManagementPage.Access, ManagementPage.Backup),
                "模型与服务" to listOf(ManagementPage.Models, ManagementPage.Media, ManagementPage.Services),
                "连接与自动化" to listOf(ManagementPage.Connectors, ManagementPage.Channels, ManagementPage.Hooks, ManagementPage.Network),
                "账号" to listOf(ManagementPage.Team, ManagementPage.Account),
            )
            groups.forEach { (title, entries) ->
            val pages = entries.filter { (!it.needsBot || bot != null) && (it != ManagementPage.Network || session.account?.kind != "cloud") && (it != ManagementPage.Team || session.account?.kind == "cloud") }
            if (pages.isNotEmpty()) item {
                SectionLabel(title)
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    pages.forEachIndexed { index, page ->
                        SettingsRow(page.title, page.description, when (page) {
                            ManagementPage.Bots -> Icons.Filled.Dashboard
                            ManagementPage.Bot -> Icons.Filled.SmartToy
                            ManagementPage.Agents -> Icons.Filled.Psychology
                            ManagementPage.Models -> Icons.Filled.ModelTraining
                            ManagementPage.Media -> Icons.Filled.PermMedia
                            ManagementPage.Services -> Icons.Filled.TravelExplore
                            ManagementPage.Connectors -> Icons.Filled.Extension
                            ManagementPage.Computers -> Icons.Filled.Computer
                            ManagementPage.Hooks -> Icons.Filled.Bolt
                            ManagementPage.Channels -> Icons.Filled.Forum
                            ManagementPage.Workspace -> Icons.Filled.Storage
                            ManagementPage.Access -> Icons.Filled.Group
                            ManagementPage.Backup -> Icons.Filled.Backup
                            ManagementPage.Network -> Icons.Filled.Hub
                            ManagementPage.Account -> Icons.Filled.AccountCircle
                            ManagementPage.Team -> Icons.Filled.Groups
                        }, index, pages.size, tone = when (page) {
                            ManagementPage.Agents, ManagementPage.Access, ManagementPage.Services,
                            ManagementPage.Connectors, ManagementPage.Hooks, ManagementPage.Team -> ListIconTone.Tertiary
                            ManagementPage.Workspace, ManagementPage.Computers, ManagementPage.Media,
                            ManagementPage.Backup, ManagementPage.Account -> ListIconTone.Secondary
                            else -> ListIconTone.Primary
                        }, onClick = { onOpenManagement(page) })
                    }
                }
            }
            }
            if (bot != null) item {
                SectionLabel("Bot 功能")
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    BotFeature.entries.forEachIndexed { index, feature ->
                        val section = MainSection.entries.first { it.feature == feature }
                        SettingsRow(feature.title, featureDescription(feature), section.icon, index = index,
                            count = BotFeature.entries.size, tone = if (feature in listOf(BotFeature.Memory, BotFeature.Usage, BotFeature.Skills)) ListIconTone.Tertiary else ListIconTone.Primary,
                            onClick = { onOpen(feature) })
                    }
                }
            }
            item { SettingsRow("离线历史", "查看、搜索和管理近期会话缓存", Icons.Filled.OfflinePin, onClick = onOpenHistory) }
            item {
                SectionLabel("个性化")
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        SettingsRow("浮动菜单", floating.mapNotNull { name -> MainSection.entries.firstOrNull { it.name == name }?.label }.joinToString(" · "),
                            Icons.Filled.Tune, index = 0, count = 3, tone = ListIconTone.Tertiary, onClick = { navigationOpen = true })
                        Box {
                            SettingsRow("外观", themeLabel(mode), Icons.Filled.Contrast, index = 1, count = 3,
                                tone = ListIconTone.Secondary, onClick = { themeOpen = true })
                            MemohPopupMenu(themeOpen, { themeOpen = false }, Alignment.TopEnd) { MemohMenuGroup {
                                ThemeMode.entries.forEachIndexed { index, item -> MemohMenuRow(themeLabel(item), Icons.Filled.Contrast,
                                    { settings.setThemeMode(item); themeOpen = false }, selected = item == mode, selectable = true, index = index, count = ThemeMode.entries.size) }
                            } }
                        }
                        Box {
                            SettingsRow("主题色", accent.label, Icons.Filled.Palette, index = 2, count = 3,
                                onClick = { accentOpen = true })
                            MemohPopupMenu(accentOpen, { accentOpen = false }, Alignment.TopEnd) { MemohMenuGroup {
                                MemohAccent.entries.forEachIndexed { index, item -> MemohMenuRow(item.label, Icons.Filled.Palette,
                                    { settings.setAccent(item); accentOpen = false }, selected = item == accent, selectable = true, index = index, count = MemohAccent.entries.size) }
                            } }
                        }
                    }
            }
            item { NotificationSettings(settings) }
            item {
                SettingsRow("退出登录", "再次使用时需要重新登录", Icons.AutoMirrored.Filled.Logout, tone = ListIconTone.Error,
                    onClick = { logoutOpen = true }, trailing = null)
                Text("Mehomo", Modifier.fillMaxWidth().padding(top = 20.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (navigationOpen) FloatingMenuSettings(floating, { navigationOpen = false }, settings::setFloatingSections)
    if (teamsOpen) AlertDialog(onDismissRequest = { teamsOpen = false }, title = { Text("Cloud 团队") }, icon = { Icon(Icons.Filled.Groups, null) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            if (teams.loading) LoadingIndicator(Modifier.size(36.dp))
            teams.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            teams.teams.forEachIndexed { index, membership -> membership.team?.let { team ->
                SettingsRow(team.name, when (membership.role?.substringAfterLast('_')?.lowercase()) { "owner" -> "所有者"; "admin" -> "管理员"; "member" -> "成员"; else -> membership.role.orEmpty() },
                    Icons.Filled.Groups, index, teams.teams.size, trailing = if (team.teamId == session.account?.teamId) Icons.Filled.Check else null,
                    onClick = { settings.selectCloudTeam(team.teamId); teamsOpen = false })
            } }
            if (!teams.loading && teams.error == null && teams.teams.isEmpty()) Text("当前账号尚未加入团队")
        } }, confirmButton = { MemohActionButton("完成", Icons.Filled.Check, { teamsOpen = false }) },
        dismissButton = { if (teams.error != null) MemohActionButton("重试", Icons.Filled.Refresh, settings::loadCloudTeams) })
    if (logoutOpen) AlertDialog(onDismissRequest = { logoutOpen = false }, icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
        title = { Text("退出登录") }, text = { Text("清除本机的当前登录凭据，再次使用时需要重新登录。") },
        confirmButton = { MemohActionButton("退出登录", Icons.AutoMirrored.Filled.Logout, { logoutOpen = false; settings.signOut() }, primary = true, danger = true) },
        dismissButton = { MemohActionButton("取消", Icons.Filled.Close, { logoutOpen = false }) })
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 12.dp, bottom = 10.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsRow(title: String, subtitle: String, icon: ImageVector, index: Int = 0, count: Int = 1,
    tone: ListIconTone = ListIconTone.Primary, trailing: ImageVector? = Icons.Filled.ChevronRight, onClick: () -> Unit) {
    SegmentedListItem(onClick = onClick, shapes = ListItemDefaults.segmentedShapes(index, count), modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = if (tone == ListIconTone.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
        leadingContent = { MemohListIcon(icon, tone) },
        trailingContent = trailing?.let { { Icon(it, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium) },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

private fun featureDescription(feature: BotFeature): String = when (feature) {
    BotFeature.Memory -> "浏览、搜索和管理长期记忆"
    BotFeature.Schedules -> "定时任务和执行记录"
    BotFeature.Usage -> "Token 用量和云端电脑状态"
    BotFeature.Apps -> "已安装应用和应用商店"
    BotFeature.Skills -> "管理技能和 SKILL.md"
    BotFeature.Mcp -> "连接外部工具和服务"
    BotFeature.Files -> "浏览、预览和编辑云端文件"
}

@Composable
private fun FloatingMenuSettings(selected: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var items by remember { mutableStateOf(selected) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义浮动菜单") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Text("选择 1–4 个入口，并调整显示顺序。其他功能仍可从‘我的’页进入；Bot 菜单也能打开设置。",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Text("显示顺序", Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary) }
            items(items.size) { index ->
                val section = MainSection.entries.firstOrNull { it.name == items[index] }
                if (section != null) Card(Modifier.fillMaxWidth(), shape = ListItemDefaults.segmentedShapes(index, items.size).shape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(section.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(section.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalIconButton(onClick = { items = items.toMutableList().apply { add(index - 1, removeAt(index)) } },
                            enabled = index > 0, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Filled.ArrowUpward, "上移 ${section.label}", Modifier.size(18.dp)) }
                        FilledTonalIconButton(onClick = { items = items.toMutableList().apply { add(index + 1, removeAt(index)) } },
                            enabled = index < items.lastIndex, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Filled.ArrowDownward, "下移 ${section.label}", Modifier.size(18.dp)) }
                    }
                }
            }
            item { Text("可选入口", Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary) }
            MainSection.entries.forEachIndexed { index, section -> item {
                val checked = section.name in items
                val canToggle = if (checked) items.size > 1 else items.size < 4
                SegmentedListItem(onClick = { items = if (checked) items - section.name else items + section.name },
                    enabled = canToggle, shapes = ListItemDefaults.segmentedShapes(index, MainSection.entries.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    leadingContent = { Icon(section.icon, null, Modifier.size(20.dp)) },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = canToggle) }) {
                    Text(section.label, style = MaterialTheme.typography.bodyMedium)
                }
            } }
            item { MemohActionButton("恢复默认", Icons.Filled.RestartAlt, { items = SettingsStore.DEFAULT_FLOATING_SECTIONS }, Modifier.padding(top = 8.dp)) }
        }
    }, confirmButton = { MemohActionButton("保存", Icons.Filled.Save, { onSave(items); onDismiss() }, primary = true) },
        dismissButton = { MemohActionButton("取消", Icons.Filled.Close, onDismiss) })
}
private fun themeLabel(mode: ThemeMode) = when (mode) { ThemeMode.System -> "跟随系统"; ThemeMode.Light -> "浅色"; ThemeMode.Dark -> "深色" }
