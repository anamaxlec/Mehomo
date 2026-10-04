package dev.memoh.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.*
import dev.memoh.core.model.*
import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*
import java.time.format.DateTimeFormatter

data class ScheduleOptions(val models: List<ChatModel> = emptyList(), val agents: List<BotAgent> = emptyList(),
    val workdirs: List<Workdir> = emptyList(), val sessions: List<Session> = emptyList(), val timezone: String? = null)

internal fun scheduleBody(original: JsonObject?, basics: JsonObject, execution: JsonObject): JsonObject {
    if (original == null) return JsonObject(basics + execution)
    val changed = execution.any { (key, value) -> (original[key] ?: if (value is JsonPrimitive && value.isString) JsonPrimitive("") else JsonNull) != value }
    if (!changed) return basics
    val readonly = setOf("id", "bot_id", "created_at", "updated_at", "current_calls", "name", "description", "command", "pattern", "enabled", "max_calls")
    return JsonObject(basics + mapOf("execution" to JsonObject(original.filterKeys { it !in readonly } + execution)))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleEditor(schedule: BotSchedule?, state: BotFeatureState, vm: BotFeatureViewModel, onDismiss: () -> Unit) {
    val wire = schedule?.id?.let { state.scheduleWire[it] }
    var name by remember { mutableStateOf(schedule?.name.orEmpty()) }
    var command by remember { mutableStateOf(schedule?.command.orEmpty()) }
    var description by remember { mutableStateOf(schedule?.description.orEmpty()) }
    var rule by remember(schedule?.id) { mutableStateOf(schedule?.pattern?.let(ScheduleRule::from) ?: ScheduleRule()) }
    var calls by remember { mutableStateOf(schedule?.maxCalls?.toString().orEmpty()) }
    var seconds by remember { mutableStateOf(schedule?.maxRunSeconds?.toString() ?: "0") }
    var target by remember { mutableStateOf(schedule?.runTarget?.takeIf(String::isNotBlank) ?: "new_session") }
    var sessionId by remember { mutableStateOf(schedule?.targetSessionId.orEmpty()) }
    var agentId by remember { mutableStateOf(schedule?.botAgentId.orEmpty()) }
    var modelId by remember { mutableStateOf(schedule?.modelId?.takeIf(String::isNotBlank) ?: schedule?.acpModelId.orEmpty()) }
    var effort by remember { mutableStateOf(schedule?.reasoningEffort.orEmpty()) }
    var workdir by remember { mutableStateOf(schedule?.workdirId.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val options = state.scheduleOptions
    val effectiveAgent = if (target == "new_session") agentId else options.sessions.firstOrNull { it.id == sessionId }?.botAgentId.orEmpty()
    val externalModel = effectiveAgent.isNotBlank() || if (target == "existing_session") options.sessions.firstOrNull { it.id == sessionId }?.isAcpRuntime == true else schedule?.runtimeType == "acp_agent" && agentId == schedule.botAgentId.orEmpty()
    LaunchedEffect(effectiveAgent, state.busy) { if (!state.busy && effectiveAgent.isNotBlank() && effectiveAgent !in state.scheduleAgentModels) vm.scheduleAgentOptions(effectiveAgent) }
    val catalog = if (effectiveAgent.isNotBlank()) state.scheduleAgentModels[effectiveAgent].orEmpty()
        else if (externalModel) emptyList() else options.models.filter { it.isSelectable }
    val model = catalog.firstOrNull { it.id == modelId }
    val expression = runCatching { rule.expression() }.getOrNull()
    val preview by produceState<java.time.ZonedDateTime?>(null, expression, options.timezone) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            options.timezone?.takeIf(String::isNotBlank)?.let { zone -> expression?.let { nextPreset(it, zone) } }
        }
    }
    MemohFormDialog(if (schedule == null) "新建日程" else "编辑日程", { if (!state.busy) onDismiss() }, confirmButton = {
        MemohActionButton("保存", Icons.Filled.Save, {
            try {
                require(name.isNotBlank() && command.isNotBlank()) { "请填写名称和执行任务" }
                val pattern = rule.expression()
                validateSchedulePattern(pattern)
                val count = calls.trim().takeIf(String::isNotBlank)?.toLongOrNull()
                require(calls.isBlank() || count != null && count > 0) { "执行次数应为正整数" }
                val timeout = seconds.toLongOrNull() ?: error("超时应为整数秒")
                require(timeout == 0L || timeout in 300L..86400L) { "超时应为 300–86400 秒，0 使用一小时" }
                require(target != "existing_session" || sessionId.isNotBlank()) { "请选择目标会话" }
                val execution = apiBody("run_target" to target, "target_session_id" to if (target == "existing_session") sessionId else "",
                    "bot_agent_id" to if (target == "new_session") agentId else "", "workdir_id" to if (target == "new_session") workdir else "",
                    "runtime_type" to if (target == "new_session" && target == (schedule?.runTarget?.takeIf(String::isNotBlank) ?: "new_session") && agentId == schedule?.botAgentId.orEmpty()) schedule?.runtimeType.orEmpty() else "",
                    "acp_agent_id" to if (target == "new_session" && target == (schedule?.runTarget?.takeIf(String::isNotBlank) ?: "new_session") && agentId == schedule?.botAgentId.orEmpty()) schedule?.acpAgentId.orEmpty() else "",
                    "model_id" to if (!externalModel) modelId else "",
                    "acp_model_id" to if (externalModel) modelId else "",
                    "reasoning_effort" to effort, "max_run_seconds" to timeout)
                vm.saveSchedule(schedule?.id, scheduleBody(wire, apiBody("name" to name.trim(), "description" to description.trim(), "command" to command.trim(),
                    "pattern" to pattern, "max_calls" to (count?.let(::JsonPrimitive) ?: JsonNull), "enabled" to (schedule?.enabled ?: true)), execution), onDismiss)
            } catch (e: Exception) { error = e.message }
        }, enabled = !state.busy && !state.scheduleOptionsLoading, primary = true)
    }, dismissButton = { TextButton(onDismiss, enabled = !state.busy) { Text("取消") } }, text = {
        Column(Modifier.heightIn(max = 570.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.scheduleOptionsLoading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadingIndicator(Modifier.size(32.dp)); Text("正在加载执行选项", style = MaterialTheme.typography.bodySmall)
            }
            state.scheduleOptionsError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                MemohActionButton("重新加载选项", Icons.Filled.Refresh, vm::scheduleOptions)
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
            Text("重复规则", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ScheduleMode.entries.forEach { mode -> ScheduleFilterChip(rule.mode == mode, mode.label) {
                    rule = rule.copy(mode = mode, advanced = if (mode == ScheduleMode.Cron) expression ?: rule.advanced else rule.advanced)
                    error = null
                } }
            }
            if (rule.mode == ScheduleMode.Cron) OutlinedTextField(rule.advanced, { rule = rule.copy(advanced = it) }, Modifier.fillMaxWidth(), label = { Text("Cron 表达式") }, supportingText = { Text("五段或六段表达式，也支持 @daily、@every 30m") })
            else if (rule.mode == ScheduleMode.Minutes) OutlinedTextField(rule.interval, { rule = rule.copy(interval = it) }, Modifier.fillMaxWidth(), label = { Text("间隔分钟（1–59）") }, singleLine = true)
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (rule.mode != ScheduleMode.Hourly) OutlinedTextField(rule.hour, { rule = rule.copy(hour = it) }, Modifier.weight(1f), label = { Text("时（0–23）") }, singleLine = true)
                    OutlinedTextField(rule.minute, { rule = rule.copy(minute = it) }, Modifier.weight(1f), label = { Text("分（0–59）") }, singleLine = true)
                }
                if (rule.mode == ScheduleMode.Weekly) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 0 to "日").forEach { (day, label) ->
                        ScheduleFilterChip(day in rule.weekdays, label) { rule = rule.copy(weekdays = if (day in rule.weekdays) rule.weekdays - day else rule.weekdays + day) }
                    }
                }
                if (rule.mode == ScheduleMode.Monthly || rule.mode == ScheduleMode.Yearly) {
                    if (rule.mode == ScheduleMode.Yearly) OutlinedTextField(rule.month, { rule = rule.copy(month = it) }, Modifier.fillMaxWidth(), label = { Text("月份（1–12）") }, singleLine = true)
                    OutlinedTextField(rule.monthDays, { rule = rule.copy(monthDays = it) }, Modifier.fillMaxWidth(), label = { Text("日期（1–31）") }, supportingText = { Text("多个日期用逗号分隔；没有该日期的月份会跳过") }, singleLine = true)
                }
            }
            if (expression != null) Text("${scheduleDescription(expression)}\nBot 时区 · ${options.timezone?.takeIf(String::isNotBlank) ?: "服务端默认"}" + (preview?.let { "\n下次执行 · " + it.format(DateTimeFormatter.ofPattern("MM-dd HH:mm z")) }.orEmpty()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), label = { Text("执行任务") }, minLines = 3)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("说明") })
            ScheduleChoice("执行会话", target, listOf("new_session" to "每次新建会话", "existing_session" to "使用已有会话")) { target = it; modelId = ""; effort = "" }
            if (target == "existing_session") ScheduleChoice("目标会话", sessionId, options.sessions.map { it.id to (it.title ?: "未命名会话") }) { sessionId = it; modelId = ""; effort = "" }
            else {
                if (externalModel && effectiveAgent.isBlank()) Text("外部 Agent · ${schedule?.acpAgentId.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                ScheduleChoice("Agent", agentId, listOf("" to "Memoh") + options.agents.filter { it.enabled != false }.map { it.id to it.name }) { agentId = it; modelId = ""; effort = "" }
                ScheduleChoice("工作目录", workdir, listOf("" to "默认目录") + options.workdirs.map { it.id to it.name }) { workdir = it }
            }
            ScheduleChoice("模型", modelId, listOf("" to "使用默认模型") +
                listOfNotNull(modelId.takeIf { it.isNotBlank() && catalog.none { model -> model.id == it } }?.let { it to it }) + catalog.map { it.id to it.label }) { modelId = it; effort = "" }
            ScheduleChoice("思考强度", effort, listOf("" to "默认") + model?.efforts.orEmpty().map { it to reasoningEffortLabel(it) }) { effort = it }
            OutlinedTextField(calls, { calls = it }, Modifier.fillMaxWidth(), label = { Text("最多执行次数（空白为不限）") }, singleLine = true)
            OutlinedTextField(seconds, { seconds = it }, Modifier.fillMaxWidth(), label = { Text("运行超时（秒，0 为一小时）") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    })
}

@Composable
private fun ScheduleChoice(label: String, value: String, choices: List<Pair<String, String>>, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton({ open = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text(choices.firstOrNull { it.first == value }?.second ?: value, Modifier.weight(1f)); Icon(Icons.Filled.ArrowDropDown, null) }
            MemohPopupMenu(open, { open = false }, Alignment.TopStart) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { MemohMenuGroup {
                    choices.forEachIndexed { index, item -> MemohMenuRow(item.second, Icons.Filled.Check, { select(item.first); open = false }, selected = value == item.first, selectable = true, index = index, count = choices.size) }
                } }
            }
        }
    }
}
