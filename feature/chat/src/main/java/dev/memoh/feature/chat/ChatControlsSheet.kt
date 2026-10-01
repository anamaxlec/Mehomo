package dev.memoh.feature.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.designsystem.component.MemohFormDialog
import dev.memoh.core.designsystem.component.MemohComposerSurface
import dev.memoh.core.designsystem.component.MemohComposerTextField
import dev.memoh.core.model.QueueItem
import dev.memoh.core.model.RuntimeModeState
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatControlsSheet(state: ChatUiState, viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(state.draft) }
    var edit by remember { mutableStateOf<Pair<QueueItem, Boolean>?>(null) }
    var editText by remember { mutableStateOf("") }
    var command by remember { mutableStateOf<String?>(null) }
    var commandArgs by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(Unit) {
        while (true) { viewModel.refreshControls(); delay(5000) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("会话控制", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (state.error ?: state.controlsError)?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            state.commandOutput?.let { ControlCard("执行结果", Icons.Filled.Terminal) { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            ControlCard("消息队列", Icons.Filled.Queue) {
                Text("后续消息在当前任务结束后执行；插话交给正在运行的任务处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MemohComposerSurface(Modifier.fillMaxWidth()) {
                    MemohComposerTextField(text, { text = it }, "追加内容", !state.busy, Modifier.fillMaxWidth())
                }
                ControlActions {
                    MemohActionButton("加入后续", Icons.Filled.PlaylistAdd, { viewModel.enqueue(text, false); text = "" }, enabled = !state.busy && text.isNotBlank(), primary = true)
                    if (state.queue.steerSupported) MemohActionButton("插话", Icons.Filled.Forum, { viewModel.enqueue(text, true); text = "" }, enabled = !state.busy && text.isNotBlank() && state.isRunning)
                }
            listOf(false to state.queue.followUp, true to state.queue.steer).forEach { (steer, queue) ->
                if (queue.isNotEmpty()) Text(if (steer) "插话" else "后续", style = MaterialTheme.typography.labelLarge)
                queue.forEachIndexed { index, item ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                            ControlActions {
                                MemohActionButton("编辑", Icons.Filled.Edit, { edit = item to steer; editText = item.text }, enabled = !state.busy)
                                if (index > 0) MemohActionButton("上移", Icons.Filled.ArrowUpward, { viewModel.moveQueueUp(item, queue[index - 1], steer) }, enabled = !state.busy)
                                if (!steer && state.queue.steerSupported && state.isRunning) MemohActionButton("转为插话", Icons.Filled.Forum, { viewModel.promoteQueue(item) }, enabled = !state.busy)
                                MemohActionButton("移出", Icons.Filled.Close, { viewModel.deleteQueue(item, steer) }, enabled = !state.busy, danger = true)
                            }
                        }
                    }
                }
            }
            }
            state.controls?.let { controls ->
                ModeChoices("权限模式", controls.modes, state.busy) { id -> viewModel.setMode(id, "permission") }
                ModeChoices("计划模式", controls.planMode, state.busy) { id -> viewModel.setMode(id, "plan") }
                if (controls.capabilities?.goal == true) {
                    ControlCard("目标", Icons.Filled.Flag) {
                    Text(state.goal?.objective ?: "当前没有目标", style = MaterialTheme.typography.bodyMedium)
                    state.goal?.let { goal ->
                        Text("${goal.status.orEmpty()} · ${goal.tokensUsed ?: 0}${goal.tokenBudget?.let { " / $it" }.orEmpty()} tokens", style = MaterialTheme.typography.bodySmall)
                        ControlActions {
                            MemohActionButton("暂停", Icons.Filled.Pause, { viewModel.goalAction("pause") }, enabled = !state.busy && goal.status == "active")
                            MemohActionButton("清除", Icons.Filled.DeleteOutline, { confirm = "清除当前目标" to { viewModel.goalAction("clear") } }, enabled = !state.busy, danger = true)
                        }
                    }
                    }
                }
                if (!controls.commands.isNullOrEmpty()) ControlCard("运行命令", Icons.Filled.Terminal) {
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        controls.commands.orEmpty().forEachIndexed { index, item ->
                            SegmentedListItem(onClick = {
                                if (item.inputHint.isNullOrBlank()) item.name?.let(viewModel::command)
                                else { command = item.name; commandArgs = "" }
                            }, enabled = !state.busy && item.name != null,
                                shapes = ListItemDefaults.segmentedShapes(index, controls.commands.orEmpty().size),
                                leadingContent = { Icon(Icons.Filled.Terminal, null, Modifier.size(18.dp)) },
                                supportingContent = item.description?.takeIf { it.isNotBlank() }?.let { description -> { Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                                trailingContent = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }) {
                                Text(item.name.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            val forkTurn = state.settledHistory.lastOrNull { it.isAssistant && it.runtimeForkable != false }?.turnId
            ControlCard("更多操作", Icons.Filled.Tune) { ControlActions {
                if (state.controls?.capabilities?.compact == true) MemohActionButton("压缩上下文", Icons.Filled.Compress, { confirm = "压缩会话上下文" to viewModel::compact }, enabled = !state.busy && !state.isRunning)
                if (forkTurn != null) MemohActionButton("从最近回复创建分支", Icons.Filled.CallSplit, { viewModel.fork(forkTurn) }, enabled = !state.busy && !state.isRunning)
                MemohActionButton("快捷操作帮助", Icons.Filled.HelpOutline, { viewModel.quickAction("help") }, enabled = !state.busy)
                MemohActionButton("可用技能", Icons.Filled.AutoAwesome, { viewModel.quickAction("skill.list") }, enabled = !state.busy)
            }
            }
        }
    }
    edit?.let { (item, steer) -> MemohFormDialog(onDismissRequest = { edit = null }, title = "编辑排队消息", text = { OutlinedTextField(editText, { editText = it }, minLines = 3) },
        confirmButton = { MemohActionButton("保存", Icons.Filled.Save, { viewModel.editQueue(item, editText, steer); edit = null }, enabled = editText.isNotBlank() && !state.busy, primary = true) }, dismissButton = { MemohActionButton("取消", Icons.Filled.Close, { edit = null }) }) }
    command?.let { name -> MemohFormDialog(onDismissRequest = { command = null }, title = name, text = { OutlinedTextField(commandArgs, { commandArgs = it }, label = { Text("命令参数") }) },
        confirmButton = { MemohActionButton("执行", Icons.Filled.PlayArrow, { viewModel.command("$name $commandArgs".trim()); command = null }, enabled = !state.busy, primary = true) }, dismissButton = { MemohActionButton("取消", Icons.Filled.Close, { command = null }) }) }
    confirm?.let { (title, action) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(title) }, text = { Text("确认对当前会话执行此操作？") },
        confirmButton = { MemohActionButton("确认", Icons.Filled.Check, { confirm = null; action() }, primary = true) }, dismissButton = { MemohActionButton("取消", Icons.Filled.Close, { confirm = null }) }) }
}

@Composable
private fun ControlCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChoices(title: String, modes: RuntimeModeState?, busy: Boolean, onSelect: (String) -> Unit) {
    if (modes?.supported != true) return
    ControlCard(title, if (title == "权限模式") Icons.Filled.Security else Icons.Filled.Assignment) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { modes.availableModes.orEmpty().forEach { mode ->
        FilterChip(selected = mode.id == modes.currentModeId, onClick = { mode.id?.let(onSelect) }, label = { Text(mode.name ?: mode.id.orEmpty()) },
            enabled = !busy, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary, selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary),
            trailingIcon = { if (mode.warning == true) Icon(Icons.Filled.WarningAmber, "此模式有额外权限") })
    } }
    }
}
