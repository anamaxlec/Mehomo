package dev.memoh.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.memoh.core.data.ShareQuickTask
import dev.memoh.core.designsystem.component.MemohFormDialog
import dev.memoh.core.designsystem.component.MemohListIcon
import dev.memoh.feature.sessions.SessionsViewModel
import java.util.UUID

@Composable
fun ShareTargetDialog(accountId: String, teamId: String, onDismiss: () -> Unit, onSelect: (String, String, String) -> Unit) {
    val viewModel: SessionsViewModel = hiltViewModel(key = "share-target")
    val quickTasks: ShareQuickTasksViewModel = hiltViewModel(key = "share-quick-tasks")
    val state by viewModel.state.collectAsState()
    val tasks by quickTasks.tasks.collectAsState()
    val tasksError by quickTasks.error.collectAsState()
    val savingTask by quickTasks.saving.collectAsState()
    LaunchedEffect(Unit) { viewModel.start(); viewModel.refresh() }
    LaunchedEffect(accountId, teamId) { quickTasks.bind(accountId, teamId) }
    var botsOpen by remember { mutableStateOf(false) }
    var selectedTaskId by remember(accountId, teamId) { mutableStateOf<String?>(null) }
    var editingTask by remember(accountId, teamId) { mutableStateOf<ShareQuickTask?>(null) }
    var creatingTask by remember(accountId, teamId) { mutableStateOf(false) }
    val selectedTask = tasks.firstOrNull { it.id == selectedTaskId }
    val taskReady = selectedTask == null || selectedTask.botId == state.bot?.id
    val sessions = state.sessions.filter { it.isLocal }

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("分享到会话", style = MaterialTheme.typography.headlineSmall)
                    Text("内容会填入草稿，由你确认后发送", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onDismiss) { Icon(Icons.Filled.Close, "关闭分享") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selectedTask == null, { selectedTaskId = null }, label = { Text("原样分享") },
                    leadingIcon = if (selectedTask == null) ({ Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null)
                tasks.forEach { task ->
                    FilterChip(task.id == selectedTaskId, {
                        selectedTaskId = task.id
                        state.bots.firstOrNull { it.id == task.botId }?.let(viewModel::selectBot)
                    }, label = { Text(task.name, maxLines = 1) })
                }
                AssistChip(onClick = { creatingTask = true }, enabled = state.bot != null,
                    label = { Text("新建快捷任务") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) })
            }
            selectedTask?.let { task ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(task.prompt, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { editingTask = task }) { Icon(Icons.Filled.Edit, "编辑${task.name}") }
                    }
                }
                if (!taskReady) Text("此任务的 Bot 不可用，请编辑任务。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            tasksError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选择会话", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box {
                    TextButton(onClick = { botsOpen = true }) {
                        Icon(Icons.Filled.SmartToy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(state.bot?.displayName ?: state.bot?.name ?: "选择 Bot", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(botsOpen, { botsOpen = false }) {
                        state.bots.forEach { bot -> DropdownMenuItem(text = { Text(bot.displayName ?: bot.name) }, onClick = {
                            viewModel.selectBot(bot)
                            if (selectedTask?.botId != bot.id) selectedTaskId = null
                            botsOpen = false
                        }) }
                    }
                }
            }
            if (state.loading && sessions.isEmpty()) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = viewModel::refresh) { Text("重试") } }
            if (!state.loading && state.bots.isEmpty() && state.error == null) Text("当前账号还没有 Bot。", style = MaterialTheme.typography.bodyMedium)
            if (!state.loading && state.bot != null && sessions.isEmpty() && state.error == null)
                Text("还没有会话，可以新建一个。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                itemsIndexed(sessions, key = { _, item -> item.id }) { index, item ->
                    SegmentedListItem(onClick = { onSelect(state.bot!!.id, item.id, selectedTask?.prompt.orEmpty()) },
                        enabled = taskReady && state.bot != null,
                        modifier = Modifier.testTag("share-target-${item.id}"),
                        shapes = ListItemDefaults.segmentedShapes(index, sessions.size),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        leadingContent = { MemohListIcon(Icons.AutoMirrored.Filled.Chat) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, null) }) {
                        Text(item.title?.takeIf(String::isNotBlank) ?: "未命名会话", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.nextCursor != null) item {
                    TextButton(onClick = viewModel::loadMore, enabled = !state.loadingMore && !state.loading) {
                        Text(if (state.loadingMore) "加载中…" else "加载更多")
                    }
                    state.loadMoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            FilledTonalButton(onClick = {
                viewModel.createSession { onSelect(it.botId ?: state.bot!!.id, it.id, selectedTask?.prompt.orEmpty()) }
            }, modifier = Modifier.fillMaxWidth(), enabled = state.bot != null && taskReady && !state.creating) {
                Icon(Icons.Filled.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("新建会话")
            }
        }
    }

    if (creatingTask || editingTask != null) {
        val original = editingTask
        var name by remember(original) { mutableStateOf(original?.name.orEmpty()) }
        var prompt by remember(original) { mutableStateOf(original?.prompt.orEmpty()) }
        var botId by remember(original) { mutableStateOf(original?.botId ?: state.bot?.id.orEmpty()) }
        var taskBotsOpen by remember(original) { mutableStateOf(false) }
        MemohFormDialog(title = if (original == null) "新建快捷任务" else "编辑快捷任务",
            onDismissRequest = { creatingTask = false; editingTask = null },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("提示词") }, minLines = 3)
                Box {
                    OutlinedButton(onClick = { taskBotsOpen = true }, modifier = Modifier.testTag("quick-task-bot")) {
                        Text(state.bots.firstOrNull { it.id == botId }?.let { it.displayName ?: it.name } ?: "选择 Bot")
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(taskBotsOpen, { taskBotsOpen = false }) {
                        state.bots.forEach { bot -> DropdownMenuItem(text = { Text(bot.displayName ?: bot.name) },
                            onClick = { botId = bot.id; taskBotsOpen = false }) }
                    }
                }
                tasksError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !savingTask && name.isNotBlank() && prompt.isNotBlank() && state.bots.any { it.id == botId }, onClick = {
                quickTasks.save(accountId, teamId, ShareQuickTask(original?.id ?: UUID.randomUUID().toString(), name.trim(), prompt.trim(), botId)) {
                    if (selectedTaskId == original?.id) selectedTaskId = null
                    creatingTask = false; editingTask = null
                }
            }) { Text("保存") } },
            dismissButton = { Row {
                if (original != null) TextButton(onClick = {
                    quickTasks.remove(accountId, teamId, original.id)
                    if (selectedTaskId == original.id) selectedTaskId = null
                    editingTask = null
                }) { Text("删除") }
                TextButton(onClick = { creatingTask = false; editingTask = null }) { Text("取消") }
            } })
    }
}
