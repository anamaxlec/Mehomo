package dev.memoh.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.*
import dev.memoh.core.network.apiBody
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.designsystem.component.LocalFloatingNavigationPadding
import dev.memoh.core.designsystem.component.MemohFormDialog
import dev.memoh.core.designsystem.component.MemohListSkeleton
import dev.memoh.core.designsystem.component.MemohPageTopBar
import dev.memoh.core.designsystem.component.MemohRefreshBox
import dev.memoh.core.designsystem.component.MemohPopupMenu
import dev.memoh.core.designsystem.component.MemohMenuGroup
import dev.memoh.core.designsystem.component.MemohMenuRow
import kotlinx.serialization.json.*
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class EditField(val key: String, val label: String, val value: String = "", val multiline: Boolean = false, val secret: Boolean = false,
    val choices: List<Pair<String, String>> = emptyList(), val visible: (Map<String, String>) -> Boolean = { true })
private data class Editor(val title: String, val fields: List<EditField>, val save: (Map<String, String>) -> Unit)
private data class Confirmation(val title: String, val message: String, val action: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotFeatureScreen(
    state: BotFeatureState,
    viewModel: BotFeatureViewModel,
    onBack: (() -> Unit)? = null,
    onOpenSession: (String) -> Unit = {},
    onOpenConnectors: (() -> Unit)? = null,
    showTitle: Boolean = true,
) {
    if (state.feature == BotFeature.Files) {
        WorkspaceFilesScreen(state, viewModel, onBack, showTitle)
        return
    }
    var editor by remember(state.botId, state.feature) { mutableStateOf<Editor?>(null) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var details by remember { mutableStateOf<Pair<String, String>?>(null) }
    var scheduleOpen by remember { mutableStateOf(false) }
    var editedSchedule by remember { mutableStateOf<BotSchedule?>(null) }
    var focusedMemory by remember { mutableStateOf<MemoryEntry?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportError by remember { mutableStateOf<String?>(null) }
    val mcpSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val config = state.mcpExport
        if (uri != null && config != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(config.toByteArray()) } }
                viewModel.closeMcpExport()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { exportError = "保存 MCP 配置失败" }
        }
    }
    fun memoryEditor(memory: MemoryEntry? = null) {
        editor = Editor(if (memory == null) "添加记忆" else "编辑记忆",
            listOf(EditField("text", "记忆内容", memory?.memory.orEmpty(), multiline = true))) {
            viewModel.saveMemory(memory?.id, it.getValue("text").trim())
        }
    }
    fun scheduleEditor(schedule: BotSchedule? = null) {
        editedSchedule = schedule
        scheduleOpen = true
        viewModel.scheduleOptions()
    }
    fun skillEditor(skill: InstalledSkill? = null) {
        editor = Editor(if (skill == null) "创建技能" else "编辑技能", listOf(EditField("raw", "SKILL.md",
            skill?.raw ?: "---\nname: my-skill\ndescription: 技能说明\n---\n\n在这里编写技能指令。", multiline = true))) {
            viewModel.saveSkill(skill?.sourcePath, it.getValue("raw"))
        }
    }
    fun mcpEditor(connection: McpConnection? = null) {
        val config = connection?.config
        editor = Editor(if (connection == null) "添加 MCP" else "编辑 MCP", listOf(
            EditField("name", "名称", connection?.name.orEmpty()),
            EditField("transport", "传输方式", connection?.type ?: "http", choices = listOf("http" to "HTTP", "sse" to "SSE", "stdio" to "stdio")),
            EditField("url", "服务地址", config?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty(), visible = { it["transport"] != "stdio" }),
            EditField("command", "启动命令", config?.get("command")?.jsonPrimitive?.contentOrNull.orEmpty(), visible = { it["transport"] == "stdio" }),
            EditField("cwd", "工作目录（可选）", config?.get("cwd")?.jsonPrimitive?.contentOrNull.orEmpty(), visible = { it["transport"] == "stdio" }),
            EditField("args", "命令参数（JSON 数组）", config?.get("args")?.toString() ?: "[]", visible = { it["transport"] == "stdio" }),
            EditField("headers", "HTTP headers（JSON 对象）", config?.get("headers")?.toString() ?: "{}", secret = true, visible = { it["transport"] != "stdio" }),
            EditField("env", "环境变量（JSON 对象）", config?.get("env")?.toString() ?: "{}", secret = true, visible = { it["transport"] == "stdio" }),
        )) { values ->
            val transport = values.getValue("transport").trim()
            require(transport in listOf("http", "sse", "stdio")) { "请选择传输方式" }
            require(if (transport != "stdio") values.getValue("url").startsWith("http") else values.getValue("command").isNotBlank()) { "请填写地址或命令" }
            val args = Json.parseToJsonElement(values.getValue("args")) as? JsonArray ?: error("参数应为 JSON 数组")
            val headers = Json.parseToJsonElement(values.getValue("headers")) as? JsonObject ?: error("headers 应为 JSON 对象")
            val env = Json.parseToJsonElement(values.getValue("env")) as? JsonObject ?: error("环境变量应为 JSON 对象")
            viewModel.saveMcp(connection?.id, apiBody("name" to values.getValue("name").trim(), "transport" to transport,
                "is_active" to (connection?.isActive ?: true), "url" to values.getValue("url").takeIf { transport != "stdio" },
                "command" to values.getValue("command").takeIf { transport == "stdio" }, "cwd" to values.getValue("cwd").takeIf { transport == "stdio" },
                "args" to args.takeIf { transport == "stdio" }, "headers" to headers.takeIf { transport != "stdio" }, "env" to env.takeIf { transport == "stdio" }))
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = if (showTitle) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentWindowInsets = if (showTitle) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0), topBar = {
        if (showTitle) MemohPageTopBar(state.feature.title, onBack = onBack, scrollBehavior = scrollBehavior,
            actions = { FilledTonalIconButton(onClick = viewModel::refresh, enabled = !state.busy && !state.loading,
                shapes = IconButtonDefaults.shapes()) { Icon(Icons.Filled.Refresh, "刷新", Modifier.size(22.dp)) } })
    }) { padding ->
        val initialLoading = state.loading && !state.hasLoaded
        MemohRefreshBox(refreshing = (state.loading && state.hasLoaded) || state.busy,
            onRefresh = viewModel::refresh, enabled = !state.busy && !initialLoading && state.botId.isNotBlank(),
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = maxOf(100.dp, LocalFloatingNavigationPadding.current)),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            item {
                if (state.botId.isBlank()) EmptyNote("请选择一个 Bot", "选择 Bot 后查看和管理它的工作空间。")
                state.error?.let { ErrorNotice(it, viewModel::refresh, viewModel::dismiss) }
                state.notice?.let {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                        ListItem(headlineContent = { Text(it, style = MaterialTheme.typography.bodyMedium) },
                            leadingContent = { Icon(Icons.Filled.Info, null, Modifier.size(20.dp)) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer))
                    }
                }
            }
            when (state.feature) {
                BotFeature.Files -> Unit
                BotFeature.Memory -> {
                    item {
                        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(state.query, viewModel::query, viewModel::refresh, "搜索记忆")
                        FeatureActions {
                            FeatureAction("添加记忆", Icons.Filled.Add, { memoryEditor() }, enabled = !state.busy, primary = true)
                            FeatureAction("关系", Icons.Filled.Hub, viewModel::showGraph, enabled = !state.busy)
                            if (state.memoryStatus?.compact?.semantic == true || state.memoryStatus?.compact?.archive == true)
                                FeatureAction("整理", Icons.Filled.AutoFixHigh,
                                    { confirmation = Confirmation("整理记忆", "合并与整理现有记忆，这可能需要一段时间。", viewModel::compactMemory) }, enabled = !state.busy)
                        }
                        state.memoryStatus?.let { status ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Storage, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("${status.sourceCount ?: state.memories.size} 条来源 · ${status.indexedCount ?: 0} 条索引 · ${status.providerType.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        }
                    }
                    if (initialLoading) item(key = "initial-feature-loading") {
                        MemohListSkeleton(description = "正在加载${state.feature.title}", detailed = true, rows = 4)
                    }
                    itemsIndexed(state.memories) { index, memory ->
                        FeatureCard(memory.memory ?: "空记忆", featureTime(memory.updatedAt ?: memory.createdAt), index, state.memories.size, titleIsBody = true) {
                            memory.score?.let { Text("相关度 · %.3f".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                            FeatureAction("详情与来源", Icons.Filled.Info, { focusedMemory = memory })
                            if (memory.id != null) {
                                FeatureActions {
                                    FeatureAction("编辑", Icons.Filled.Edit, { memoryEditor(memory) }, enabled = !state.busy)
                                    FeatureAction("删除", Icons.Filled.DeleteOutline,
                                        { confirmation = Confirmation("删除记忆", memory.memory.orEmpty(), { viewModel.deleteMemory(requireNotNull(memory.id)) }) }, enabled = !state.busy, danger = true)
                                }
                            }
                        }
                    }
                    if (!state.loading && state.memories.isEmpty()) item { EmptyNote("暂无记忆", "添加一条，或让 Bot 在对话中记录需要长期保留的信息。") }
                }
                BotFeature.Schedules -> {
                    item { Box(Modifier.padding(bottom = 14.dp)) { FeatureAction("新建日程", Icons.Filled.Add,
                        { scheduleEditor() }, enabled = !state.busy && state.botId.isNotBlank(), primary = true) } }
                    if (initialLoading) item(key = "initial-feature-loading") {
                        MemohListSkeleton(description = "正在加载${state.feature.title}", detailed = true, rows = 4)
                    }
                    itemsIndexed(state.schedules) { index, schedule ->
                        FeatureCard(schedule.name ?: "未命名日程", "${schedule.pattern.orEmpty()} · 已运行 ${schedule.currentCalls ?: 0} 次${schedule.maxCalls?.let { " / $it" }.orEmpty()}", index, state.schedules.size) {
                            Column {
                                Text(schedule.command.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (schedule.enabled == true) "已启用" else "已暂停", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                    Switch(checked = schedule.enabled == true, onCheckedChange = { viewModel.toggleSchedule(schedule) }, enabled = !state.busy)
                                }
                                FeatureActions {
                                    FeatureAction("编辑", Icons.Filled.Edit, { scheduleEditor(schedule) }, enabled = !state.busy)
                                    FeatureAction("执行记录", Icons.Filled.History, { viewModel.logs(schedule) }, enabled = !state.busy)
                                    FeatureAction("删除", Icons.Filled.DeleteOutline,
                                        { confirmation = Confirmation("删除日程", "停止并删除 ${schedule.name.orEmpty()}。", { schedule.id?.let(viewModel::deleteSchedule) }) }, enabled = !state.busy, danger = true)
                                }
                            }
                        }
                    }
                    if (!state.loading && state.schedules.isEmpty()) item { EmptyNote("暂无日程", "给 Bot 安排一个周期性执行的任务。") }
                }
                BotFeature.Usage -> {
                    item {
                        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ButtonGroup(modifier = Modifier.fillMaxWidth(), overflowIndicator = {}) {
                                    listOf(7, 30, 90).forEach { days -> toggleableItem(checked = state.usageDays == days,
                                        onCheckedChange = { if (state.usageDays != days) viewModel.usageDays(days) }, enabled = !state.busy,
                                        label = "近 $days 天", weight = 1f) }
                                }
                                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    ListItem(
                                        headlineContent = { Text("${state.usageFrom} — ${java.time.LocalDate.parse(state.usageTo).minusDays(1)}",
                                            style = MaterialTheme.typography.bodyMedium) },
                                        supportingContent = { Text("统计时区 · UTC", style = MaterialTheme.typography.labelSmall) },
                                        leadingContent = { Icon(Icons.Filled.DateRange, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    )
                                }
                            }
                        }
                        }
                    }
                    if (initialLoading) item(key = "initial-feature-loading") {
                        MemohListSkeleton(description = "正在加载${state.feature.title}", detailed = true, rows = 4)
                    }
                    item(key = "usage-summary") {
                        FeatureSwap(state.usage) { usage ->
                        if (usage != null) {
                        val all = usage.chat.orEmpty() + usage.discuss.orEmpty() + usage.schedule.orEmpty() + usage.acpAgent.orEmpty()
                        FeatureCard("Token 用量", "所选时间内的服务端记录", 0, if (state.metrics != null) 2 else 1) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                UsageTokenStats(all.sumOf { it.inputTokens ?: 0 }, all.sumOf { it.outputTokens ?: 0 })
                                UsageStatRow(
                                    first = { UsageStat("推理", format(all.sumOf { it.reasoningTokens ?: 0 }), Icons.Filled.Psychology, it) },
                                    second = { UsageStat("缓存读取", format(all.sumOf { it.cacheReadTokens ?: 0 }), Icons.Filled.Cached, it) },
                                )
                            }
                        }
                        }
                        }
                    }
                    state.metrics?.let { metrics -> item {
                        FeatureCard("云端电脑", "资源监控", if (state.usage != null) 1 else 0, if (state.usage != null) 2 else 1) {
                            UsageResources(metrics)
                        }
                    } }
                    if (!state.usage?.byModel.isNullOrEmpty()) item { FeatureSectionLabel("模型用量") }
                    itemsIndexed(state.usage?.byModel.orEmpty(), key = { index, model -> "model:${model.modelId ?: model.modelSlug}:$index" }) { index, model ->
                        FeatureSwap(model, Modifier.animateItem()) { shown ->
                        FeatureCard(usageModelName(shown.modelName, shown.modelSlug), null, index, state.usage?.byModel.orEmpty().size) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                shown.providerName?.takeIf(String::isNotBlank)?.let { UsageInfo("供应商 · $it", Icons.Filled.Cloud) }
                                UsageTokenStats(shown.inputTokens ?: 0, shown.outputTokens ?: 0)
                            }
                        }
                        }
                    }
                    if (!initialLoading) item {
                        FeatureSectionLabel("使用记录${if (state.recordsError == null && state.usage != null) "（${state.recordCount}）" else ""}")
                        state.recordsError?.let { ErrorNotice(it, viewModel::refresh, viewModel::dismiss) }
                        if (!state.loading && state.usage != null && state.recordsError == null && state.records.isEmpty())
                            EmptyNote("暂无使用记录", "所选时间内还没有模型调用记录。")
                    }
                    itemsIndexed(state.records, key = { index, record -> "record:${record.id ?: index}" }) { index, record ->
                        FeatureSwap(record, Modifier.animateItem()) { shown -> UsageRecordCard(shown, index, state.records.size, onOpenSession) }
                    }
                    if (state.records.size < state.recordCount) item { FeatureActions { FeatureAction("加载更多记录", Icons.Filled.ExpandMore, viewModel::moreRecords, enabled = !state.busy && !state.loading) } }
                }
                BotFeature.Apps, BotFeature.Skills -> {
                    val apps = state.feature == BotFeature.Apps
                    item {
                        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ButtonGroup(modifier = Modifier.fillMaxWidth(), overflowIndicator = {}) {
                            toggleableItem(checked = !state.browsing, onCheckedChange = { if (state.browsing) viewModel.browse(false) },
                                label = "已安装", icon = { Icon(Icons.Filled.Inventory2, null, Modifier.size(18.dp)) }, weight = 1f)
                            toggleableItem(checked = state.browsing, onCheckedChange = { if (!state.browsing) viewModel.browse(true) },
                                label = "商店", icon = { Icon(Icons.Filled.Storefront, null, Modifier.size(18.dp)) }, weight = 1f)
                        }
                        FeatureSwap(state.browsing) { browsing ->
                        if (!browsing) FeatureAction(if (apps) "检查更新" else "创建技能", if (apps) Icons.Filled.Update else Icons.Filled.Add,
                            { if (apps) viewModel.checkUpdates() else skillEditor() }, enabled = !state.busy, primary = true)
                        else SearchField(state.query, viewModel::query, viewModel::refresh, if (apps) "搜索应用" else "搜索技能")
                        }
                        if (state.progress.isNotEmpty()) Card(shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) { Text("操作进度", style = MaterialTheme.typography.labelLarge); state.progress.takeLast(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) } }
                        }
                        }
                    }
                    if (initialLoading) item(key = "initial-feature-loading") {
                        MemohListSkeleton(description = "正在加载${state.feature.title}", detailed = true, rows = 4)
                    }
                    if (state.browsing && apps) itemsIndexed(state.marketApps, key = { index, app -> "market-app:${app.appId}:$index" }) { index, app -> FeatureCard(app.name ?: app.appId ?: "应用", app.description, index, state.marketApps.size, modifier = Modifier.animateItem()) {
                        FeatureActions { FeatureAction("安装 ${app.version.orEmpty()}".trim(), Icons.Filled.Download,
                            { confirmation = Confirmation("安装 ${app.name.orEmpty()}", "将应用及所需依赖安装到当前 Bot。", { if (app.registryId != null && app.appId != null) viewModel.install(requireNotNull(app.registryId), requireNotNull(app.appId)) }) }, enabled = !state.busy && app.registryId != null && app.appId != null) }
                    } }
                    else if (state.browsing) itemsIndexed(state.marketSkills, key = { index, skill -> "market-skill:${skill.skillId}:$index" }) { index, skill -> FeatureCard(skill.name ?: skill.skillId ?: "技能", skill.description, index, state.marketSkills.size, modifier = Modifier.animateItem()) {
                        FeatureActions { FeatureAction("安装所属应用", Icons.Filled.Download,
                            { confirmation = Confirmation("安装技能所属应用", "此技能属于 ${skill.appId.orEmpty()}，将同时安装应用中的其他组件。", { if (skill.registryId != null && skill.appId != null) viewModel.install(requireNotNull(skill.registryId), requireNotNull(skill.appId)) }) }, enabled = !state.busy && skill.registryId != null && skill.appId != null) }
                    } }
                    else if (apps) itemsIndexed(state.apps, key = { index, app -> "installed-app:${app.appId}:$index" }) { index, app -> FeatureCard(app.name ?: app.appId ?: "应用",
                        listOf(app.version, featureStatus(app.status)).filterNot { it.isNullOrBlank() }.joinToString(" · "), index, state.apps.size, modifier = Modifier.animateItem()) {
                        Column {
                            app.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Text(app.description.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            FeatureActions {
                                FeatureAction("更新", Icons.Filled.Update, { viewModel.update(app) }, enabled = !state.busy && app.registryId != null && app.appId != null)
                                if (app.status == "needs_auth" || app.connectors.orEmpty().any { it.status != "active" }) onOpenConnectors?.let { open ->
                                    FeatureAction("连接外部服务", Icons.Filled.Link, open, enabled = !state.busy)
                                }
                                if (app.status != "installed") FeatureAction("继续安装", Icons.Filled.PlayArrow, { viewModel.resume(app) }, enabled = !state.busy && app.installationId != null)
                                FeatureAction("移除", Icons.Filled.DeleteOutline, { viewModel.previewRemove(app) }, enabled = !state.busy && app.installationId != null, danger = true)
                            }
                            app.connectors.orEmpty().forEach { connector ->
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Link, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("${connector.type.orEmpty()} · ${featureStatus(connector.status).orEmpty()}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } }
                    else itemsIndexed(state.skills, key = { index, skill -> "installed-skill:${skill.sourcePath}:$index" }) { index, skill -> FeatureCard(skill.name ?: "技能", skill.description, index, state.skills.size, modifier = Modifier.animateItem()) {
                        Column {
                            Text("${if (skill.managed == true) "应用管理" else "自定义"} · ${featureStatus(skill.state).orEmpty()}", style = MaterialTheme.typography.labelSmall)
                            FeatureActions {
                                FeatureAction("查看", Icons.Filled.Description, { details = (skill.name ?: "技能") to (skill.raw ?: skill.content.orEmpty()) })
                                if (skill.editable == true) FeatureAction("编辑", Icons.Filled.Edit, { skillEditor(skill) }, enabled = !state.busy)
                                if (skill.sourcePath != null) FeatureAction(if (skill.state == "disabled") "启用" else "停用",
                                    if (skill.state == "disabled") Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    { viewModel.toggleSkill(skill) }, enabled = !state.busy)
                                if (skill.deletable == true && skill.sourcePath != null) FeatureAction("删除", Icons.Filled.DeleteOutline,
                                    { confirmation = Confirmation("删除技能", "删除 ${skill.name.orEmpty()}。", { viewModel.deleteSkill(requireNotNull(skill.sourcePath)) }) }, enabled = !state.busy, danger = true)
                            }
                        }
                    } }
                    val count = if (apps) state.marketApps.size else state.marketSkills.size
                    if (state.browsing && count < state.marketTotal) item { FeatureActions { FeatureAction("加载更多", Icons.Filled.ExpandMore, viewModel::moreMarket, enabled = !state.busy) } }
                    if (!state.loading && (if (state.browsing) count == 0 else if (apps) state.apps.isEmpty() else state.skills.isEmpty())) item { EmptyNote("暂无${if (apps) "应用" else "技能"}", if (state.browsing) "尝试其他关键词。" else "到商店浏览并安装。") }
                }
                BotFeature.Mcp -> {
                    item { FeatureActions {
                        FeatureAction("添加 MCP", Icons.Filled.Add, { mcpEditor() }, enabled = !state.busy, primary = true)
                        FeatureAction("导入 JSON", Icons.Filled.FileUpload, {
                            editor = Editor("导入 MCP", listOf(EditField("config", "MCP 配置", "{\"mcpServers\":{}}", multiline = true))) { values ->
                                val config = Json.parseToJsonElement(values.getValue("config")) as? JsonObject ?: error("MCP 配置应为 JSON 对象")
                                val wrapped = if ("mcpServers" in config) config else JsonObject(mapOf("mcpServers" to config))
                                require(wrapped["mcpServers"] is JsonObject) { "mcpServers 应为 JSON 对象" }
                                viewModel.importMcp(wrapped)
                            }
                        }, enabled = !state.busy)
                        FeatureAction("导出配置", Icons.Filled.FileDownload, viewModel::exportMcp, enabled = !state.busy && state.connections.isNotEmpty())
                    } }
                    if (initialLoading) item(key = "initial-feature-loading") {
                        MemohListSkeleton(description = "正在加载${state.feature.title}", detailed = true, rows = 4)
                    }
                    itemsIndexed(state.connections) { index, connection -> FeatureCard(connection.name ?: "MCP", "${connection.type.orEmpty()} · ${featureStatus(connection.status).orEmpty()}", index, state.connections.size) {
                        Column {
                            connection.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("${connection.toolsCache.orEmpty().size} 个工具", style = MaterialTheme.typography.labelMedium)
                            FeatureActions {
                                FeatureAction("编辑", Icons.Filled.Edit, { mcpEditor(connection) }, enabled = !state.busy)
                                FeatureAction("检查连接", Icons.Filled.NetworkCheck, { connection.id?.let(viewModel::probeMcp) }, enabled = !state.busy)
                                if (connection.type in listOf("http", "sse")) FeatureAction("账号授权", Icons.Filled.Login, { viewModel.mcpAuthorization(connection) }, enabled = !state.busy)
                                FeatureAction(if (connection.isActive == true) "停用" else "启用", Icons.Filled.PowerSettingsNew, { viewModel.toggleMcp(connection) }, enabled = !state.busy)
                                FeatureAction("查看工具", Icons.Filled.Build, { details = (connection.name ?: "MCP") to connection.toolsCache.orEmpty().joinToString("\n\n") { tool ->
                                    listOfNotNull(tool.name, tool.description, tool.inputSchema?.let { "参数\n${Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), it)}" }).joinToString("\n")
                                } }, enabled = connection.toolsCache.orEmpty().isNotEmpty())
                                FeatureAction("删除", Icons.Filled.DeleteOutline,
                                    { confirmation = Confirmation("删除 MCP", "断开并删除 ${connection.name.orEmpty()}。", { connection.id?.let(viewModel::deleteMcp) }) }, enabled = !state.busy, danger = true)
                            }
                            connection.toolsCache.orEmpty().take(8).forEach { tool -> Text(tool.name.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                        }
                    } }
                    if (!state.loading && state.connections.isEmpty()) item { EmptyNote("暂无 MCP 连接", "添加服务，让 Bot 使用更多工具。") }
                }
            }
        }
        }
    }
    editor?.let { value -> EditDialog(value, state, onDismiss = { editor = null }) }
    state.mcpAuthorization?.let { McpAuthorizationDialog(it, state.busy, state.error, viewModel) }
    state.mcpExport?.let { config -> AlertDialog(onDismissRequest = viewModel::closeMcpExport, title = { Text("导出 MCP 配置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("配置可用于其他客户端，可能包含连接凭据。保存后请妥善保管。")
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }, confirmButton = { FeatureAction("保存 JSON", Icons.Filled.FileDownload, { exportError = null; mcpSaver.launch("mcp-config.json") }, primary = true) },
        dismissButton = { FeatureAction("取消", Icons.Filled.Close, viewModel::closeMcpExport) }) }
    confirmation?.let { value -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(value.title) }, text = { Text(value.message) },
        confirmButton = { FeatureAction("确认", Icons.Filled.Check, { confirmation = null; value.action() }, primary = true) },
        dismissButton = { FeatureAction("取消", Icons.Filled.Close, { confirmation = null }) }) }
    details?.let { (title, text) -> ContentDialog(title, { details = null }) { Text(text, style = MaterialTheme.typography.bodyMedium) } }
    state.graph?.let { graph -> ContentDialog("记忆关系", viewModel::closeGraph) {
        Text("${graph.nodes.orEmpty().size} 个节点 · ${graph.edges.orEmpty().size} 条关系", style = MaterialTheme.typography.labelLarge)
        graph.nodes.orEmpty().forEachIndexed { index, node ->
            SegmentedListItem(onClick = {
                val memory = (state.memories + state.graphMemories).firstOrNull { it.id in node.memoryIds.orEmpty() || it.id == node.id }
                focusedMemory = memory ?: MemoryEntry(id = node.id, memory = node.memory ?: node.label, metadata = node.metadata)
            }, shapes = ListItemDefaults.segmentedShapes(index, graph.nodes.orEmpty().size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                leadingContent = { Icon(Icons.Filled.Hub, null) }, supportingContent = { Text("${node.count ?: node.memoryIds.orEmpty().size} 条关联记忆") },
                trailingContent = { Icon(Icons.Filled.ChevronRight, null) }) { Text(node.label ?: node.memory ?: node.id.orEmpty()) }
        }
        graph.edges.orEmpty().forEach { edge ->
            val source = graph.nodes.orEmpty().firstOrNull { it.id == edge.source }
            val target = graph.nodes.orEmpty().firstOrNull { it.id == edge.target }
            Text("${source?.label ?: source?.memory ?: edge.source} → ${edge.rel.orEmpty()} → ${target?.label ?: target?.memory ?: edge.target}", style = MaterialTheme.typography.bodyMedium)
        }
    } }
    focusedMemory?.let { memory -> ContentDialog("记忆详情", { focusedMemory = null }) {
        Text(memory.memory.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        memory.createdAt?.let { Text("记录时间 · ${featureTime(it)}", style = MaterialTheme.typography.bodySmall) }
        memory.updatedAt?.let { Text("更新时间 · ${featureTime(it)}", style = MaterialTheme.typography.bodySmall) }
        memory.score?.let { Text("相关度 · %.3f".format(it), style = MaterialTheme.typography.bodySmall) }
        memory.metadata?.takeIf { it.isNotEmpty() }?.let { metadata ->
            val sessionId = (metadata["session_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            sessionId?.let { FeatureAction("打开来源会话", Icons.AutoMirrored.Filled.Chat, { focusedMemory = null; viewModel.closeGraph(); onOpenSession(it) }) }
            Text(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), metadata), style = MaterialTheme.typography.bodySmall)
        }
        val node = state.graph?.nodes?.firstOrNull { it.id == memory.id || memory.id in it.memoryIds.orEmpty() }
        node?.memoryIds.orEmpty().forEach { id -> (state.memories + state.graphMemories).firstOrNull { it.id == id }?.let { related ->
            TextButton({ focusedMemory = related }) { Text(related.memory ?: id) }
        } }
    } }
    if (scheduleOpen) ScheduleEditor(editedSchedule, state, viewModel) { scheduleOpen = false }
    state.logs?.let { logs -> ContentDialog(state.logSchedule?.name ?: "执行记录", viewModel::closeLogs) {
        if (logs.items.isNullOrEmpty()) Text("暂无执行记录")
        logs.items.orEmpty().forEach { log ->
            Text("${log.status.orEmpty()} · ${log.startedAt.orEmpty()}", style = MaterialTheme.typography.labelLarge)
            Text(log.resultText ?: log.errorMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            log.sessionId?.let { id -> FeatureActions { FeatureAction("查看执行会话", Icons.AutoMirrored.Filled.Chat,
                { viewModel.closeLogs(); onOpenSession(id) }) } }
            HorizontalDivider()
        }
    } }
    state.removal?.let { (app, preview) ->
        val dependencies = preview["dependencies"] as? JsonArray
        val connectors = preview["connectors"] as? JsonArray
        val requiredApps = preview["required_apps"] as? JsonArray
        AlertDialog(onDismissRequest = viewModel::cancelRemove, title = { Text("移除 ${app.name.orEmpty()}") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("移除应用及其管理的技能。服务器预览的影响如下：")
                dependencies.orEmpty().forEach { value ->
                    val item = value.jsonObject
                    Text("${if (item["action"]?.jsonPrimitive?.contentOrNull == "remove") "移除" else "保留"}依赖：${item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
                connectors.orEmpty().forEach { value ->
                    val item = value.jsonObject
                    val action = when (item["action"]?.jsonPrimitive?.contentOrNull) { "disconnect" -> "断开"; "keep" -> "保留"; else -> "未连接" }
                    Text("$action · ${item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
                if (!requiredApps.isNullOrEmpty()) Text("这些应用仍依赖它：" + requiredApps.joinToString("、") { it.jsonObject["app_id"]?.jsonPrimitive?.contentOrNull.orEmpty() }, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { FeatureAction("移除", Icons.Filled.DeleteOutline, viewModel::confirmRemove, enabled = !state.busy, danger = true) },
            dismissButton = { FeatureAction("取消", Icons.Filled.Close, viewModel::cancelRemove) })
    }
}

@Composable private fun EditDialog(editor: Editor, state: BotFeatureState, onDismiss: () -> Unit) {
    var values by remember(editor) { mutableStateOf(editor.fields.associate { it.key to it.value }) }
    var validation by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy, state.notice, state.error) { if (submitted && !state.busy && state.error == null && state.notice != null) onDismiss() }
    MemohFormDialog(onDismissRequest = { if (!state.busy) onDismiss() }, title = editor.title, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            editor.fields.filter { it.visible(values) }.forEach { field ->
                if (field.choices.isNotEmpty()) {
                    var expanded by remember(field.key) { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                            Text("${field.label}：${field.choices.firstOrNull { it.first == values[field.key] }?.second.orEmpty()}", Modifier.weight(1f)); Icon(Icons.Filled.ArrowDropDown, null)
                        }
                        MemohPopupMenu(expanded, { expanded = false }, Alignment.TopStart) { MemohMenuGroup {
                            field.choices.forEachIndexed { index, choice -> MemohMenuRow(choice.second, Icons.Filled.Check, { values = values + (field.key to choice.first); expanded = false },
                                selected = values[field.key] == choice.first, selectable = true, index = index, count = field.choices.size) }
                        } }
                    }
                } else OutlinedTextField(value = values.getValue(field.key), onValueChange = { values = values + (field.key to it) },
                label = { Text(field.label, style = MaterialTheme.typography.bodySmall) }, singleLine = !field.multiline, minLines = if (field.multiline) 4 else 1,
                visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = MaterialTheme.typography.bodyMedium, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            }
            (validation ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { FeatureAction("保存", Icons.Filled.Save, {
        try {
            val optional = if (editor.fields.any { it.key == "transport" }) listOf("url", "command", "cwd") else listOf("limit")
            require(editor.fields.filter { it.key !in optional && it.visible(values) }.all { values[it.key]?.isNotBlank() == true }) { "请填写必填项" }
            validation = null; editor.save(values); submitted = true
        } catch (e: Exception) { validation = e.message ?: "请检查输入" }
    }, enabled = !state.busy, primary = true) }, dismissButton = { FeatureAction("取消", Icons.Filled.Close, onDismiss, enabled = !state.busy) })
}

@Composable private fun SearchField(query: String, onChange: (String) -> Unit, onSearch: () -> Unit, hint: String) {
    SearchBarDefaults.InputField(
        query = query,
        onQueryChange = onChange,
        onSearch = { onSearch() },
        expanded = true,
        onExpandedChange = {},
        placeholder = { Text(hint, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "搜索", Modifier.size(20.dp)) } },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange(""); onSearch() }) {
                Icon(Icons.Filled.Close, "清除搜索", Modifier.size(20.dp))
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
/** Preserve the outgoing value while the new result arrives and changes size. */
@Composable private fun <T> FeatureSwap(value: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val motion = MaterialTheme.motionScheme
    val drift = with(LocalDensity.current) { 20.dp.roundToPx() }
    AnimatedContent(targetState = value, modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) + slideInHorizontally(motion.defaultSpatialSpec()) { drift })
                .togetherWith(fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally(motion.defaultSpatialSpec()) { -drift / 2 })
                .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motion.defaultSpatialSpec() }))
        }, label = "featureResult") { shown -> content(shown) }
}

@Composable private fun FeatureCard(title: String, subtitle: String?, index: Int = 0, count: Int = 1,
    titleIsBody: Boolean = false, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = ListItemDefaults.segmentedShapes(index, count).shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = if (titleIsBody) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), content = content)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FeatureActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable private fun FeatureAction(label: String, icon: ImageVector, onClick: () -> Unit,
    enabled: Boolean = true, primary: Boolean = false, danger: Boolean = false) {
    MemohActionButton(label, icon, onClick, enabled = enabled, primary = primary, danger = danger)
}

@Composable private fun FeatureSectionLabel(label: String) {
    Text(label, Modifier.padding(start = 12.dp, top = 20.dp, bottom = 10.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable private fun ContentDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = { FeatureAction("关闭", Icons.Filled.Close, onDismiss) })
}
@Composable private fun ErrorNotice(message: String, retry: () -> Unit, dismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            FeatureActions { FeatureAction("重试", Icons.Filled.Refresh, retry); FeatureAction("关闭", Icons.Filled.Close, dismiss) }
        }
    }
}
@Composable private fun EmptyNote(title: String, subtitle: String) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun UsageTokenStats(input: Long, output: Long) {
    val colors = MaterialTheme.colorScheme
    UsageStatRow(
        first = { UsageStat("输入", format(input), Icons.Filled.ArrowDownward, it, colors.primaryContainer, colors.onPrimaryContainer) },
        second = { UsageStat("输出", format(output), Icons.Filled.ArrowUpward, it, colors.tertiaryContainer, colors.onTertiaryContainer) },
    )
}

@Composable private fun UsageStatRow(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 288.dp * LocalDensity.current.fontScale) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

@Composable private fun UsageStat(label: String, value: String, icon: ImageVector, modifier: Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = containerColor, contentColor = contentColor) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable private fun UsageInfo(label: String, icon: ImageVector, emphasized: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium,
        color = if (emphasized) colors.secondaryContainer else colors.surfaceContainerLow,
        contentColor = if (emphasized) colors.onSecondaryContainer else colors.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun UsageRecordCard(record: TokenUsageRecord, index: Int, count: Int, onOpenSession: (String) -> Unit) {
    FeatureCard(usageModelName(record.modelName, record.modelSlug), null, index, count) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                featureTime(record.createdAt)?.takeIf(String::isNotBlank)?.let { UsageInfo(it, Icons.Filled.Schedule) }
                usageSessionType(record.sessionType)?.let { (label, icon) -> UsageInfo(label, icon, emphasized = true) }
                record.providerName?.takeIf(String::isNotBlank)?.let { UsageInfo("供应商 · $it", Icons.Filled.Cloud) }
            }
            UsageTokenStats(record.inputTokens ?: 0, record.outputTokens ?: 0)
            if ((record.reasoningTokens ?: 0) > 0 || (record.cacheReadTokens ?: 0) > 0) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    record.reasoningTokens?.takeIf { it > 0 }?.let { UsageInfo("推理 ${format(it)}", Icons.Filled.Psychology) }
                    record.cacheReadTokens?.takeIf { it > 0 }?.let { UsageInfo("缓存读取 ${format(it)}", Icons.Filled.Cached) }
                }
            }
            record.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                FeatureAction("查看会话", Icons.AutoMirrored.Filled.Chat, { onOpenSession(sessionId) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun UsageResources(metrics: ContainerMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (metrics.supported == false) {
            UsageResourceRow("暂不提供资源监控", "当前云端电脑不提供 CPU、内存与存储指标。", Icons.Filled.Info)
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.status?.taskRunning?.let { running -> UsageInfo(if (running) "任务进行中" else "空闲",
                    if (running) Icons.Filled.HourglassTop else Icons.Filled.CheckCircle, emphasized = true) }
                featureTime(metrics.sampledAt)?.let { UsageInfo("更新于 $it", Icons.Filled.Schedule) }
            }
            UsageResourceRow("CPU", metrics.metrics?.cpu?.usagePercent?.let { "%.1f%%".format(it) } ?: "—", Icons.Filled.Speed)
            UsageResourceRow("内存", "${resourceBytes(metrics.metrics?.memory?.usageBytes)} / ${resourceBytes(metrics.metrics?.memory?.limitBytes)}", Icons.Filled.Memory)
            UsageResourceRow("存储", resourceBytes(metrics.metrics?.storage?.usedBytes), Icons.Filled.Storage)
        }
    }
}

@Composable private fun UsageResourceRow(label: String, value: String, icon: ImageVector) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(label, style = MaterialTheme.typography.labelLarge) },
            supportingContent = { Text(value, style = MaterialTheme.typography.bodyMedium) },
            leadingContent = { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
}

private fun usageSessionType(value: String?): Pair<String, ImageVector>? = when (value) {
    "chat" -> "对话" to Icons.AutoMirrored.Filled.Chat
    "discuss" -> "讨论" to Icons.Filled.Forum
    "schedule" -> "定时任务" to Icons.Filled.EventRepeat
    "acp_agent" -> "Agent" to Icons.Filled.SmartToy
    null, "" -> null
    else -> "其他会话" to Icons.AutoMirrored.Filled.Chat
}

private fun usageModelName(name: String?, slug: String?): String = name?.takeIf(String::isNotBlank)
    ?: slug?.takeIf(String::isNotBlank) ?: "未知模型"

private fun resourceBytes(value: Long?): String = value?.let {
    when {
        it >= (1L shl 30) -> "%.1f GiB".format(it / (1L shl 30).toDouble())
        it >= (1L shl 20) -> "%.1f MiB".format(it / (1L shl 20).toDouble())
        it >= (1L shl 10) -> "%.1f KiB".format(it / (1L shl 10).toDouble())
        else -> "$it B"
    }
} ?: "—"
private fun featureTime(value: String?): String? = value?.let {
    runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrDefault(it)
}
private fun featureStatus(value: String?): String? = when (value) {
    "installed" -> "已安装"
    "discovered" -> "待安装"
    "installing" -> "安装中"
    "failed", "error" -> "失败"
    "needs_auth" -> "等待授权"
    "enabled", "active" -> "已启用"
    "effective" -> "已生效"
    "disabled", "inactive" -> "已停用"
    "linked", "connected" -> "已连接"
    "disconnected" -> "未连接"
    else -> value
}
private fun format(value: Long) = NumberFormat.getIntegerInstance().format(value)
