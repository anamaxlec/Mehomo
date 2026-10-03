package dev.memoh.feature.bots

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.memoh.core.designsystem.component.*
import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*

internal data class MenuAction(val label: String, val icon: ImageVector = Icons.Filled.Edit, val run: () -> Unit)
internal data class ConfirmAction(val title: String, val message: String, val run: () -> Unit)
private val pretty = Json { prettyPrint = true }
internal fun formatted(value: JsonElement) = pretty.encodeToString(JsonElement.serializer(), value)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManagementScreen(state: ManagementState, vm: ManagementViewModel, onBack: () -> Unit,
    onOpenPage: (ManagementPage) -> Unit, onOpenApps: () -> Unit, onOpenBot: (String) -> Unit) {
    var editor by remember(state.page, state.botId) { mutableStateOf<ManagementEditor?>(null) }
    var confirmation by remember { mutableStateOf<ConfirmAction?>(null) }
    var filter by remember { mutableStateOf("") }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var transcriptionModel by remember { mutableStateOf<JsonObject?>(null) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val model = transcriptionModel; transcriptionModel = null
        if (uri != null && model != null) vm.transcribe(context, model, uri)
    }
    val base = listOf("bots", state.botId)
    val settings = state.data["settings"].obj()
    val models = state.data["models"].objects()
    val agents = state.data["agents"].objects("items")
    fun edit(title: String, fields: List<FormField>, path: List<String>, method: String = "PUT", result: Boolean = false, transform: (JsonObject) -> JsonObject = { it }) {
        vm.dismiss()
        editor = ManagementEditor(title, fields) { body, saved -> vm.write(path, method, transform(body), result = result, onSaved = saved) }
    }
    fun settingField(key: String, label: String, kind: FieldKind = FieldKind.Text, choices: List<Pair<String, String>> = emptyList(), multiline: Boolean = false) =
        FormField(key, label, if (kind == FieldKind.Object) settings[key]?.toString() ?: "{}" else settings.text(key), kind, choices, multiline)
    fun modelChoices(type: String = "chat") = listOf("" to "未设置") + (when (type) {
        "chat" -> models.filter { it.text("type") == type }
        "image" -> models.filter { JsonPrimitive("image-output") in (it["config"].obj()["compatibilities"] as? JsonArray).orEmpty() }
        else -> state.data["$type-models"].objects()
    }).map { it.text("id") to it.text("name").ifBlank { it.text("model_id") } }
    fun providerEditor(provider: JsonObject? = null, media: Boolean = false) {
        val config = provider?.get("config").obj()
        edit(if (provider == null) "添加供应商" else "编辑供应商", listOf(
            FormField("name", "名称", provider?.text("name").orEmpty(), required = true),
            FormField("client_type", "接口类型", provider?.text("client_type") ?: "openai-completions", choices = (if (media) listOf(provider?.text("client_type").orEmpty()) else listOf("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai", "openai-codex", "github-copilot")).map { it to it }),
            FormField("base_url", "API 地址", config.text("base_url")),
            FormField("api_key", "API key（留空保留）", secret = true, omitBlank = true),
            FormField("config", "高级配置", formatted(JsonObject(config.filterKeys { it != "api_key" })), FieldKind.Object, multiline = true),
        ), if (provider == null) listOf("providers") else listOf("providers", provider.text("id")), if (provider == null) "POST" else "PUT") { body ->
            buildJsonObject {
                put("name", body.getValue("name")); put("client_type", body.getValue("client_type"))
                put("config", JsonObject(body["config"].obj() + mapOf("base_url" to body.getValue("base_url")) + body.filterKeys { it == "api_key" }))
            }
        }
    }
    fun modelEditor(model: JsonObject? = null) {
        val config = model?.get("config").obj()
        edit(if (model == null) "添加模型" else "编辑模型", listOf(
            FormField("name", "显示名称（空白使用模型 ID）", model?.text("name").orEmpty()),
            FormField("model_id", "模型 ID", model?.text("model_id").orEmpty(), required = true),
            FormField("provider_id", "供应商", model?.text("provider_id").orEmpty(), choices = state.data["providers"].objects().map { it.text("id") to it.text("name") }, required = true),
            FormField("type", "模型类型", model?.text("type") ?: "chat", choices = listOf("chat" to "对话", "embedding" to "Embedding", "speech" to "语音", "transcription" to "转录", "video" to "视频")),
            FormField("enable", "启用", (model?.flag("enable", true) ?: true).toString(), FieldKind.Toggle),
            FormField("context_window", "上下文窗口（tokens）", config.text("context_window"), FieldKind.Number, min = 1),
            FormField("reasoning_efforts", "支持的思考强度", config["reasoning_efforts"]?.toString() ?: "[]", FieldKind.StringSet,
                choices = listOf("disable", "minimal", "low", "medium", "high", "xhigh", "max").map { it to it }),
            FormField("compatibilities", "模型能力", config["compatibilities"]?.toString() ?: "[]", FieldKind.StringSet,
                choices = listOf("vision" to "识别图片", "tool-call" to "工具调用", "image-output" to "生成图片", "reasoning" to "推理")),
            FormField("description", "说明", config.text("description"), multiline = true),
            FormField("config", "高级模型能力", formatted(JsonObject(config.filterKeys { it !in setOf("context_window", "reasoning_efforts", "compatibilities", "description") })), FieldKind.Object, multiline = true),
        ), if (model == null) listOf("models") else listOf("models", model.text("id")), if (model == null) "POST" else "PUT") { body ->
            JsonObject(body.filterKeys { it !in setOf("context_window", "reasoning_efforts", "compatibilities", "description", "config") } + ("config" to JsonObject(body["config"].obj() +
                body.filterKeys { it in setOf("context_window", "reasoning_efforts", "compatibilities", "description") && body[it] != JsonNull })))
        }
    }
    fun agentEditor(agent: JsonObject? = null) { vm.dismiss(); editor = agentEditor(state, agent, vm) }
    fun authorize(agent: JsonObject, oauth: Boolean) {
        if (oauth && agent.text("runtime") == "codex") { vm.authorizeAgent(agent, "openai_codex_oauth"); return }
        val kind = if (oauth) "claude_code_oauth" else if (agent.text("runtime") == "codex") "openai_api_key" else "anthropic_api_key"
        editor = ManagementEditor(if (oauth) "连接 Claude Code" else "连接 API key", listOf(
            FormField("secret", if (oauth) "claude setup-token 生成的 token" else "API key", secret = true, required = true),
        )) { body, saved -> vm.authorizeAgent(agent, kind, body.text("secret")); saved() }
    }
    fun connectorAuth(app: JsonObject, type: String, catalog: JsonObject, method: JsonObject) {
        val path = base + listOf("apps", app.text("installation_id"), "connectors", type)
        val authMethod = method.text("key")
        if (method.text("type") == "oauth2" || method.text("type") == "oauth") {
            vm.write(path + "oauth", "POST", apiBody("auth_method" to authMethod), result = true)
        } else {
            val fields = method["credential_fields"].objects().map { field -> FormField(field.text("key"), field.text("label").ifBlank { field.text("key") },
                field.text("default_value"), choices = (field["options"] as? JsonArray)?.map { it.jsonPrimitive.content to it.jsonPrimitive.content }.orEmpty(),
                secret = field.flag("secret"), required = field.flag("required")) }
            edit("连接 ${catalog.text("name")}", fields, path + "api-key", "POST") { apiBody("auth_method" to authMethod, "fields" to it) }
        }
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(Modifier.nestedScroll(scroll.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        topBar = { MemohPageTopBar(state.page.title, onBack, scrollBehavior = scroll, actions = {
            FilledTonalIconButton(vm::refresh, enabled = !state.busy && !state.loading, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Filled.Refresh, "刷新") }
        }) }) { padding ->
        MemohRefreshBox(state.loading && state.loaded, vm::refresh, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), enabled = !state.busy && !state.loading) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, maxOf(32.dp, LocalFloatingNavigationPadding.current)), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                item {
                    Text(if (state.page.needsBot) state.bot?.displayName ?: state.bot?.name ?: "当前 Bot" else if (state.page == ManagementPage.Account) "当前账号" else if (state.cloud) "团队配置" else "服务配置", Modifier.padding(start = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (state.loaded && state.page.needsBot && !state.canManage) ManagementNotice("当前账号只能查看此 Bot，管理操作需要 manage 权限。")
                    state.error?.let { ManagementNotice(it, true) }
                    state.notice?.let { ManagementNotice(it) }
                }
                if (state.loading && !state.loaded) item { MemohListSkeleton(leading = true, detailed = true) }
                if (state.loaded) when (state.page) {
                    ManagementPage.Team -> cloudTeamTools(state, vm, { editor = it }, { confirmation = it }) { uriHandler.openUri(it) }
                    ManagementPage.Workspace, ManagementPage.Access, ManagementPage.Network, ManagementPage.Account ->
                        managementExtras(state, vm, { editor = it; vm.dismiss() }, { confirmation = it })
                    ManagementPage.Backup -> item { BackupTools(state, vm, onOpenBot) }
                    ManagementPage.Bots -> {
                        item { Actions { MemohActionButton("创建 Bot", Icons.Filled.Add, {
                            edit("创建 Bot", listOf(FormField("name", "名称", required = true), FormField("display_name", "显示名称"),
                                FormField("timezone", "时区", java.time.ZoneId.systemDefault().id), FormField("is_active", "启用", "true", FieldKind.Toggle)),
                                listOf("bots"), "POST", transform = ::validTimezone)
                        }, enabled = !state.busy, primary = true) } }
                        val bots = state.data["bots"].objects("items")
                        itemsIndexed(bots, key = { _, it -> it.text("id") }) { index, bot ->
                            val manage = (bot["current_user_permissions"] as? JsonArray)?.contains(JsonPrimitive("manage")) == true
                            ManagementRow(bot.text("display_name").ifBlank { bot.text("name") },
                                listOf(bot.text("status"), if (bot.flag("is_active", true)) "已启用" else "已停用").filter(String::isNotBlank).joinToString(" · "),
                                Icons.Filled.SmartToy, index, bots.size, onClick = { onOpenBot(bot.text("id")) },
                                actions = if (manage) listOf(MenuAction("删除 Bot", Icons.Filled.Delete) {
                                    confirmation = ConfirmAction("删除 Bot", "将删除 ${bot.text("name")} 及其会话和配置。") { vm.write(listOf("bots", bot.text("id")), "DELETE") }
                                }) else emptyList())
                        }
                        if (bots.isEmpty()) item { ManagementNotice("尚未创建 Bot。") }
                    }
                    ManagementPage.Bot -> {
                        item { GroupLabel("基础信息") }
                        item { ManagementRow(state.bot?.displayName ?: state.bot?.name.orEmpty(), state.bot?.timezone.orEmpty(), Icons.Filled.SmartToy, enabled = state.canManage && !state.busy,
                            onClick = { edit("基础信息", listOf(FormField("name", "Bot 名称", state.bot?.name.orEmpty(), required = true),
                                FormField("display_name", "显示名称", state.bot?.displayName.orEmpty()), FormField("avatar_url", "头像地址", state.bot?.avatarUrl.orEmpty()),
                                FormField("timezone", "时区", state.bot?.timezone.orEmpty(), omitBlank = true), FormField("is_active", "启用 Bot", (state.bot?.isActive ?: true).toString(), FieldKind.Toggle)), base, transform = ::validTimezone) }) }
                        item { GroupLabel("对话与运行") }
                        item { ManagementRow("默认 Agent 与模型", "应用于新建会话", Icons.Filled.AutoAwesome, 0, 3, state.canManage && !state.busy, onClick = {
                            edit("默认对话", listOf(settingField("default_bot_agent_id", "默认 Agent", choices = listOf("" to "Memoh") + agents.map { it.text("id") to it.text("name") }),
                                settingField("chat_model_id", "对话模型", choices = modelChoices()),
                                settingField("reasoning_effort", "思考强度").copy(choicesFor = { values ->
                                    val selected = models.firstOrNull { it.text("id") == values["chat_model_id"] }.obj()
                                    val efforts = selected["reasoning_options"].obj()["efforts"] as? JsonArray ?: selected["config"].obj()["reasoning_efforts"] as? JsonArray
                                    listOf("" to "模型默认") + efforts.orEmpty().map { it.jsonPrimitive.content to it.jsonPrimitive.content }
                                }),
                                settingField("command_ui_language", "命令语言", choices = listOf("" to "默认", "zh" to "中文", "en" to "English"))), base + "settings")
                        }) }
                        item { ManagementRow("上下文压缩", "自动压缩、阈值与压缩模型", Icons.Filled.UnfoldLess, 1, 3, state.canManage && !state.busy, onClick = {
                            edit("上下文压缩", listOf(settingField("compaction_enabled", "自动压缩", FieldKind.Toggle).copy(value = settings.flag("compaction_enabled").toString()),
                                settingField("compaction_threshold", "触发阈值（tokens，0 为自动）", FieldKind.Number).copy(min = 0), settingField("compaction_target_percent", "压缩后占比（%）", FieldKind.Number).copy(min = 1, max = 100),
                                settingField("compaction_model_id", "压缩模型", choices = modelChoices()), settingField("discuss_probe_model_id", "讨论探测模型", choices = modelChoices())), base + "settings")
                        }) }
                        item { ManagementRow("工具与审批", "执行策略与工具结果显示", Icons.Filled.Security, 2, 3, state.canManage && !state.busy, onClick = {
                            vm.dismiss(); editor = approvalEditor(state, vm)
                        }) }
                        item { GroupLabel("能力配置") }
                        item { ManagementRow("记忆、搜索与网页读取", "选择已配置的服务", Icons.Filled.Extension, 0, 4, state.canManage && !state.busy, onClick = {
                            edit("工具服务", listOf("memory" to "记忆服务", "search" to "搜索服务", "fetch" to "网页读取").map { (key, label) ->
                                settingField("${key}_provider_id", label, choices = listOf("" to "未设置") + state.data["$key-providers"].objects().map { it.text("id") to it.text("name") }) }, base + "settings")
                        }) }
                        item { ManagementRow("多媒体", "图片、语音、转录和视频", Icons.Filled.PermMedia, 1, 4, state.canManage && !state.busy, onClick = {
                            edit("多媒体模型", listOf(settingField("image_model_id", "图片模型", choices = modelChoices("image")), settingField("tts_model_id", "语音模型", choices = modelChoices("speech")),
                                settingField("transcription_model_id", "转录模型", choices = modelChoices("transcription")), settingField("video_model_id", "视频模型", choices = modelChoices("video")),
                                settingField("display_enabled", "启用桌面显示", FieldKind.Toggle).copy(value = settings.flag("display_enabled").toString())), base + "settings")
                        }) }
                        item { ManagementRow("Agent 设置", "认证、运行配置和启停", Icons.Filled.Psychology, 2, 4, onClick = { onOpenPage(ManagementPage.Agents) }) }
                        item { ManagementRow("模型设置", "供应商与模型目录", Icons.Filled.ModelTraining, 3, 4, onClick = { onOpenPage(ManagementPage.Models) }) }
                    }
                    ManagementPage.Agents -> {
                        item { Actions { MemohActionButton("添加 Agent", Icons.Filled.Add, { agentEditor() }, enabled = state.canManage && !state.busy, primary = true) } }
                        itemsIndexed(agents, key = { _, it -> it.text("id") }) { index, agent ->
                            val default = settings.text("default_bot_agent_id") == agent.text("id")
                            val actions = listOf(
                                MenuAction("运行配置", Icons.Filled.Tune) { agentEditor(agent) },
                                MenuAction("设为默认", Icons.Filled.Star) { vm.write(base + "settings", "PUT", apiBody("default_bot_agent_id" to agent.text("id"))) },
                                MenuAction("账号授权", Icons.Filled.Login) { if (agent.text("runtime") in listOf("codex", "claude-code")) authorize(agent, true) else editor = acpEditor(state, agent, vm) },
                                MenuAction("API key", Icons.Filled.Key) { if (agent.text("runtime") in listOf("codex", "claude-code")) authorize(agent, false) else editor = acpEditor(state, agent, vm) },
                                MenuAction("断开认证", Icons.Filled.LinkOff) { confirmation = ConfirmAction("断开 Agent 认证", "下一次启动此 Agent 时需要重新连接。") { vm.write(base + listOf("agents", agent.text("id"), "credential"), "DELETE") } },
                                MenuAction("删除 Agent", Icons.Filled.Delete) { confirmation = ConfirmAction("删除 Agent", agent.text("name")) { vm.write(base + listOf("agents", agent.text("id")), "DELETE") } },
                            )
                            ManagementRow(agent.text("name"), listOfNotNull(agent.text("runtime"), if (default) "默认" else null, if (agent.text("agent_credential_id").isNotBlank()) "已连接" else "待配置").joinToString(" · "),
                                Icons.Filled.Psychology, index, agents.size, state.canManage && !state.busy, onClick = { agentEditor(agent) }, actions = actions,
                                checked = agent.flag("enabled"), onChecked = { vm.enableAgent(agent, it) })
                        }
                        if (agents.isEmpty()) item { ManagementNotice("尚未添加 Agent。Memoh 默认运行方式仍可在对话中使用。") }
                    }
                    ManagementPage.Models -> {
                        val providers = state.data["providers"].objects()
                        val templates = state.data["templates"].objects().filter { it.text("domain") == "llm" }
                        item { Actions {
                            MemohActionButton("供应商", Icons.Filled.Add, { providerEditor() }, enabled = state.canManage && !state.busy, primary = true)
                            MemohActionButton("模型", Icons.Filled.Add, { modelEditor() }, enabled = state.canManage && !state.busy && providers.isNotEmpty())
                        } }
                        if (templates.isNotEmpty()) item { Actions {
                            CatalogAddButton("按模板接入", templates, !state.busy) { template ->
                                val defaults = template["default_config"].obj()
                                edit("接入 ${template.text("name")}", listOf(FormField("name", "名称", template.text("name"), required = true)) +
                                    schemaFields(template["config_schema"].obj()["fields"].obj().ifEmpty { template["config_schema"].obj()["properties"].obj() }, defaults, false),
                                    listOf("providers", "from-template"), "POST") { body -> apiBody("template_id" to template.text("id"), "domain" to "llm", "name" to body.text("name"), "config" to JsonObject(defaults + body.filterKeys { it != "name" })) }
                            }
                        } }
                        item { OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true, label = { Text("查找供应商或模型") }, leadingIcon = { Icon(Icons.Filled.Search, null) }) }
                        item { GroupLabel("供应商") }
                        val visibleProviders = providers.filter { filter.isBlank() || it.text("name").contains(filter, true) }
                        itemsIndexed(visibleProviders, key = { _, it -> "provider:${it.text("id")}" }) { index, provider ->
                            val id = provider.text("id")
                            ManagementRow(provider.text("name"), provider.text("client_type"), Icons.Filled.Cloud, index, visibleProviders.size, state.canManage && !state.busy,
                                onClick = { providerEditor(provider) }, checked = provider.flag("enable", true), onChecked = { vm.write(listOf("providers", id), "PUT", apiBody("enable" to it)) }, actions = listOf(
                                    MenuAction("测试连接", Icons.Filled.NetworkCheck) { vm.write(listOf("providers", id, "test"), "POST", result = true) },
                                    MenuAction("导入模型", Icons.Filled.CloudDownload) { vm.write(listOf("providers", id, "import-models"), "POST", apiBody(), result = true) },
                                    MenuAction("账号授权", Icons.Filled.Login) { vm.providerAuthorization(id) },
                                    MenuAction("授权状态", Icons.Filled.Info) { vm.write(listOf("providers", id, "oauth", "status"), "GET", result = true) },
                                    MenuAction("检查授权", Icons.Filled.Refresh) { vm.write(listOf("providers", id, "oauth", "poll"), "POST", result = true) },
                                    MenuAction("撤销授权", Icons.Filled.LinkOff) { confirmation = ConfirmAction("撤销供应商授权", provider.text("name")) { vm.write(listOf("providers", id, "oauth", "token"), "DELETE") } },
                                    MenuAction("删除", Icons.Filled.Delete) { confirmation = ConfirmAction("删除供应商", "${provider.text("name")} 及其模型将不可用。") { vm.write(listOf("providers", id), "DELETE") } },
                                ))
                        }
                        item { GroupLabel("模型") }
                        val visibleModels = models.filter { filter.isBlank() || (it.text("name") + it.text("model_id") + it.text("provider_name")).contains(filter, true) }
                        itemsIndexed(visibleModels, key = { _, it -> "model:${it.text("id")}" }) { index, model ->
                            val providerName = providers.firstOrNull { it.text("id") == model.text("provider_id") }?.text("name").orEmpty()
                            ManagementRow(model.text("name").ifBlank { model.text("model_id") }, listOf(providerName, model.text("type"), model.text("model_id")).filter(String::isNotBlank).joinToString(" · "), Icons.Filled.ModelTraining,
                                index, visibleModels.size, state.canManage && !state.busy, onClick = { modelEditor(model) }, checked = model.flag("enable", true),
                                onChecked = { vm.write(listOf("models", model.text("id")), "PUT", modelUpdate(model, it)) }, actions = listOf(
                                    MenuAction("测试模型", Icons.Filled.PlayArrow) { vm.write(listOf("models", model.text("id"), "test"), "POST", result = true) },
                                    MenuAction("删除", Icons.Filled.Delete) { confirmation = ConfirmAction("删除模型", model.text("name")) { vm.write(listOf("models", model.text("id")), "DELETE") } },
                                ))
                        }
                        if (providers.isEmpty()) item { ManagementNotice("添加供应商后，可导入或添加模型。") }
                    }
                    ManagementPage.Connectors -> {
                        item { Actions { MemohActionButton("应用商店", Icons.Filled.Apps, onOpenApps) } }
                        val catalog = state.data["catalog"].objects()
                        val connections = state.data["connections"].objects("items")
                        item { GroupLabel("已连接") }
                        itemsIndexed(connections, key = { _, it -> it.text("connection_id") }) { index, connection ->
                            val path = base + listOf("connectors", connection.text("connection_id"))
                            ManagementRow(connection.text("alias").ifBlank { connection.text("connector_type") }, connection.text("status"), Icons.Filled.Link,
                                index, connections.size, state.canManage && !state.busy, onClick = { vm.write(path, "GET", result = true) }, checked = connection.flag("enabled", true),
                                onChecked = { vm.write(path, "PATCH", apiBody("enabled" to it)) }, actions = listOf(
                                    MenuAction("重新授权", Icons.Filled.Login) { vm.write(path + "reauth", "POST", result = true) },
                                    MenuAction("断开", Icons.Filled.LinkOff) { confirmation = ConfirmAction("断开连接器", connection.text("connector_type")) { vm.write(path, "DELETE") } },
                                ))
                        }
                        val apps = state.data["apps"].objects("items")
                        apps.forEach { app -> app["connectors"].objects().filter { it.text("status") != "linked" }.forEach { connector ->
                            val type = connector.text("type")
                            val entry = catalog.firstOrNull { it.text("type") == type }
                            item(key = "auth:${app.text("installation_id")}:$type") {
                                ManagementRow(entry?.text("name") ?: type, "${app.text("name")} · 待授权", Icons.Filled.Key, enabled = state.canManage && !state.busy,
                                    actions = entry?.get("auth_methods").objects().map { method -> MenuAction(method.text("label"), Icons.Filled.Login) { connectorAuth(app, type, entry!!, method) } }, onClick = {})
                            }
                        } }
                        item { GroupLabel("连接器目录") }
                        itemsIndexed(catalog, key = { _, it -> "catalog:${it.text("type")}" }) { index, entry ->
                            ManagementRow(entry.text("name"), entry.text("description"), Icons.Filled.Extension, index, catalog.size,
                                onClick = { onOpenApps() })
                        }
                        if (catalog.isEmpty()) item { ManagementNotice("当前服务没有开放连接器目录。") }
                    }
                    ManagementPage.Services -> {
                        listOf("search" to "搜索", "fetch" to "网页读取", "memory" to "记忆").forEach { (kind, label) ->
                            val entries = state.data["$kind-providers"].objects()
                            val catalog = state.data["$kind-meta"].objects()
                            item { GroupLabel(label) }
                            item { Actions {
                                if (catalog.isNotEmpty()) CatalogAddButton("添加${label}服务", catalog, !state.busy) { type ->
                                    edit("添加${label}服务", listOf(FormField("name", "名称", required = true)) + schemaFields(type["config_schema"].obj()["fields"].obj(), JsonObject(emptyMap()), false),
                                        listOf("$kind-providers"), "POST") { body -> apiBody("name" to body.text("name"), "provider" to type.text("provider"), "config" to JsonObject(body.filterKeys { it != "name" })) }
                                }
                            } }
                            itemsIndexed(entries, key = { _, it -> "$kind:${it.text("id")}" }) { index, entry ->
                                val path = listOf("$kind-providers", entry.text("id"))
                                val type = catalog.firstOrNull { it.text("provider") == entry.text("provider") }.obj()
                                ManagementRow(entry.text("name"), type.text("display_name").ifBlank { entry.text("provider") }, Icons.Filled.Extension,
                                    index, entries.size, enabled = !state.busy, onClick = {
                                        val config = entry["config"].obj()
                                        edit("${label}服务", listOf(FormField("name", "名称", entry.text("name"), required = true)) + schemaFields(type["config_schema"].obj()["fields"].obj(), config, true), path) { body ->
                                            apiBody("name" to body.text("name"), "provider" to entry.text("provider"), "enable" to entry.flag("enable", true),
                                                "config" to JsonObject(config + body.filterKeys { it != "name" }))
                                        }
                                    }, checked = entry.flag("enable", true), onChecked = {
                                        vm.write(path, "PUT", JsonObject(entry.filterKeys { key -> key in setOf("name", "provider", "config") } + ("enable" to JsonPrimitive(it))))
                                    }, actions = listOf(MenuAction("删除服务", Icons.Filled.Delete) { confirmation = ConfirmAction("删除${label}服务", entry.text("name")) { vm.write(path, "DELETE") } }))
                            }
                            if (kind + "-providers" !in state.data) item { ManagementNotice("当前服务未提供${label}配置接口。") }
                        }
                    }
                    ManagementPage.Media -> {
                        val templates = state.data["templates"].objects()
                        listOf("speech" to "语音合成", "transcription" to "音频转录", "video" to "视频").forEach { (kind, label) ->
                            item { GroupLabel(label) }
                            item { Actions {
                                val available = templates.filter { it.text("domain") == kind }
                                if (available.isNotEmpty()) CatalogAddButton("接入${label}供应商", available, !state.busy) { template ->
                                    val defaults = template["default_config"].obj()
                                    edit("接入 ${template.text("name")}", listOf(FormField("name", "名称", template.text("name"), required = true)) +
                                        schemaFields(template["config_schema"].obj()["fields"].obj().ifEmpty { template["config_schema"].obj()["properties"].obj() }, defaults, false),
                                        listOf("providers", "from-template"), "POST") { body -> apiBody("template_id" to template.text("id"), "domain" to kind, "name" to body.text("name"), "config" to JsonObject(defaults + body.filterKeys { it != "name" })) }
                                }
                            } }
                            val providers = state.data["$kind-providers"].objects()
                            itemsIndexed(providers, key = { _, it -> "$kind:provider:${it.text("id")}" }) { index, provider ->
                                ManagementRow(provider.text("name"), provider.text("client_type"), if (kind == "video") Icons.Filled.Videocam else Icons.Filled.RecordVoiceOver, index, providers.size,
                                    enabled = !state.busy, onClick = { providerEditor(provider, media = true) }, checked = provider.flag("enable", true),
                                    onChecked = { vm.write(listOf("providers", provider.text("id")), "PUT", apiBody("enable" to it)) }, actions = listOf(
                                        MenuAction("导入模型", Icons.Filled.CloudDownload) { vm.write(listOf("$kind-providers", provider.text("id"), "import-models"), "POST", result = true) },
                                        MenuAction("删除供应商", Icons.Filled.Delete) { confirmation = ConfirmAction("删除供应商", provider.text("name")) { vm.write(listOf("providers", provider.text("id")), "DELETE") } },
                                    ))
                            }
                            val entries = state.data["$kind-models"].objects()
                            itemsIndexed(entries, key = { _, it -> "$kind:model:${it.text("id")}" }) { index, model ->
                                ManagementRow(model.text("name").ifBlank { model.text("model_id") }, model.text("model_id"), Icons.Filled.GraphicEq, index, entries.size,
                                    enabled = !state.busy, onClick = { vm.mediaModelFields(kind, model) { capabilities ->
                                        val config = model["config"].obj()
                                        val schema = capabilities["config_schema"].obj()["fields"]
                                        val fieldMap = if (schema is JsonArray) JsonObject(schema.objects().associate { it.text("key") to it }) else schema.obj()
                                        val fields = schemaFields(fieldMap, config, true).map { field ->
                                            if (field.key == "voice" && capabilities["voices"].objects().isNotEmpty()) field.copy(choices = capabilities["voices"].objects().map { it.text("id") to it.text("name").ifBlank { it.text("id") } }) else field
                                        }
                                        val keys = fields.map { it.key }.toSet()
                                        edit("${label}模型", listOf(FormField("name", "显示名称", model.text("name"))) + fields +
                                            listOf(FormField("advanced", "高级参数", formatted(JsonObject(config.filterKeys { it !in keys })), FieldKind.Object, multiline = true)),
                                            listOf("$kind-models", model.text("id"))) { body -> apiBody("name" to body.text("name"), "config" to JsonObject(body["advanced"].obj() + body.filterKeys { it != "name" && it != "advanced" && body[it] != JsonNull })) }
                                    } }, actions = if (kind == "speech") listOf(MenuAction("试听", Icons.Filled.PlayArrow) {
                                        editor = ManagementEditor("语音试听", listOf(FormField("text", "试听文字", required = true, multiline = true))) { body, saved -> vm.synthesize(model, body.text("text"), saved) }
                                    }) else if (kind == "transcription") listOf(MenuAction("测试转录", Icons.Filled.AudioFile) { transcriptionModel = model; audioPicker.launch(arrayOf("audio/*")) }) else emptyList())
                            }
                            if ("$kind-providers" !in state.data) item { ManagementNotice("当前服务未提供${label}配置。") }
                            else if (providers.isEmpty() && templates.none { it.text("domain") == kind }) item { ManagementNotice("当前服务没有提供${label}供应商模板。") }
                        }
                    }
                    ManagementPage.Computers -> {
                        item { Actions { MemohActionButton("接入电脑", Icons.Filled.Add, {
                            edit("接入电脑", listOf(FormField("name", "电脑名称")), listOf("users", "me", "runtimes"), "POST", result = true)
                        }, enabled = state.canManage && !state.busy, primary = true) } }
                        item { GroupLabel("我的电脑") }
                        val runtimes = state.data["runtimes"].objects()
                        itemsIndexed(runtimes, key = { _, it -> it.text("id") }) { index, runtime ->
                            ManagementRow(runtime.text("name").ifBlank { runtime.text("hostname").ifBlank { "新电脑" } },
                                listOf(if (runtime.flag("online")) "在线" else "离线", runtime.text("os"), runtime.text("client_version")).filter(String::isNotBlank).joinToString(" · "),
                                Icons.Filled.Computer, index, runtimes.size, state.canManage && !state.busy, onClick = { vm.showResult(apiBody("command" to vm.runtimeCommand(runtime))) }, actions = listOf(
                                    MenuAction("接入命令", Icons.Filled.Terminal) { vm.showResult(apiBody("command" to vm.runtimeCommand(runtime))) },

                                    MenuAction("撤销接入凭据", Icons.Filled.Delete) { confirmation = ConfirmAction("撤销电脑凭据", "${runtime.text("name")} 将断开连接。") { vm.write(listOf("users", "me", "runtimes", runtime.text("id")), "DELETE") } },
                                ) + if (state.canManageBot) listOf(MenuAction("授权当前 Bot", Icons.Filled.Link) { vm.write(base + listOf("workspace-targets", "remotes", runtime.text("id")), "PUT") }) else emptyList())
                        }
                        item { GroupLabel("当前 Bot 的执行位置") }
                        val targets = state.data["targets"].objects("targets")
                        itemsIndexed(targets, key = { _, it -> it.text("target_id") }) { index, target ->
                            val path = base + listOf("workspace-targets", target.text("target_id"))
                            val policy = target["tool_approval"].obj()
                            ManagementRow(target.text("name"), "${if (target.flag("online")) "在线" else "离线"}${if (target.flag("primary")) " · 默认" else ""}", Icons.Filled.Laptop, index, targets.size,
                                state.canManageBot && !state.busy, onClick = {
                                    edit("读写与执行权限", listOf("read" to "读取", "write" to "写入", "exec" to "执行").map { (key, label) -> FormField(key, label,
                                        policy.text(key).ifBlank { if (key == "read") "allow" else "ask" }, choices = listOf("allow" to "允许", "ask" to "询问", "deny" to "拒绝")) }, path + "tool-approval")
                                }, actions = listOf(
                                    MenuAction("设为默认", Icons.Filled.Star) { vm.write(base + listOf("workspace-targets", "primary"), "PUT", apiBody("target_id" to target.text("target_id"))) },
                                    ) + if (target.text("runtime_id").isNotBlank()) listOf(MenuAction("移除 Bot 授权", Icons.Filled.LinkOff) { confirmation = ConfirmAction("移除电脑授权", target.text("name")) { vm.write(path, "DELETE") } }) else emptyList())
                        }
                    }
                    ManagementPage.Hooks -> {
                        val hookConfig = runCatching { Json.parseToJsonElement((state.data["hooks"] as? JsonPrimitive)?.content.orEmpty()).obj() }.getOrNull()
                        if (hookConfig != null) item { ManagementRow("启用 Hooks", "使用当前工作空间的事件配置", Icons.Filled.Bolt,
                            enabled = state.canManage && !state.busy, checked = hookConfig.flag("enabled", true), onChecked = {
                                vm.saveHooks(formatted(JsonObject(hookConfig + ("enabled" to JsonPrimitive(it))))) {}
                            }) }
                        item { Actions {
                            MemohActionButton("模板", Icons.Filled.Description, {
                                editor = ManagementEditor("工具日志模板", listOf(FormField("config", "hooks.json", """{"version":1,"enabled":false,"hooks":[{"name":"工具结果日志","event":"PostToolUse","enabled":true,"actions":[{"type":"command","command":"mkdir -p .memoh && cat >> .memoh/hooks.log","on_error":"ignore"}]}]}""", FieldKind.Object, multiline = true))) { body, saved -> vm.saveHooks(formatted(body.getValue("config")), saved) }
                            }, enabled = state.canManage && !state.busy)
                            MemohActionButton("编辑配置", Icons.Filled.Edit, {
                                vm.dismiss()
                                editor = ManagementEditor("Hooks 配置", listOf(FormField("config", "hooks.json", (state.data["hooks"] as? JsonPrimitive)?.content.orEmpty(), FieldKind.Object, multiline = true))) { body, saved -> vm.saveHooks(formatted(body.getValue("config")), saved) }
                            }, enabled = state.canManage && !state.busy, primary = true)
                            MemohActionButton("测试", Icons.Filled.PlayArrow, {
                                edit("测试 Hooks", listOf(FormField("event", "事件", "PreToolUse", choices = state.data["events"].objects("events").filter { it.flag("runtime_supported") }.map { it.text("name") to it.text("name") }),
                                    FormField("payload", "测试事件内容", "{}", FieldKind.Object, multiline = true)), base + listOf("hooks", "test"), "POST", result = true) { body -> JsonObject(body["payload"].obj() + mapOf("event" to body.getValue("event"))) }
                            }, enabled = state.canManage && !state.busy)
                        } }
                        item { ManagementNotice("测试会执行当前配置中的动作。事件的实际支持状态如下。") }
                        val events = state.data["events"].objects("events")
                        itemsIndexed(events, key = { _, it -> it.text("name") }) { index, event -> ManagementRow(event.text("name"), if (event.flag("runtime_supported")) "运行时支持" else "仅在事件目录中声明", Icons.Filled.Bolt, index, events.size) }
                        item { Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Text((state.data["hooks"] as? JsonPrimitive)?.content.orEmpty(), Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                    ManagementPage.Channels -> {
                        val channels = state.data["channels"].objects()
                        val configs = state.data["configs"].obj()
                        itemsIndexed(channels, key = { _, it -> it.text("type") }) { index, channel ->
                            val type = channel.text("type")
                            val config = configs[type].obj()
                            val configured = config.isNotEmpty()
                            val ownerOnly = channel.flag("owner_only")
                            val editable = state.canManage && !state.busy && (!ownerOnly || state.bot?.ownerUserId == state.data["channel-current-user"].obj().text("id"))
                            val fields = channel["config_schema"].obj()["fields"].obj().entries.sortedBy { (it.value.obj()["order"] as? JsonPrimitive)?.intOrNull ?: 0 }
                            ManagementRow(channel.text("display_name").ifBlank { type }, if (ownerOnly && !editable) "仅 Bot 所有者可配置" else if (configured) if (config.flag("disabled")) "已停用" else "已连接" else "待配置", Icons.Filled.Forum,
                                index, channels.size, editable && !channel.flag("configless"), onClick = {
                                    val credentials = config["credentials"].obj()
                                    edit(channel.text("display_name").ifBlank { type }, schemaFields(JsonObject(fields.associate { it.key to it.value }), credentials, configured) + listOf(FormField("routing", "路由", formatted(config["routing"].obj()), FieldKind.Object, multiline = true)), base + listOf("channel", type)) { body ->
                                        apiBody("credentials" to JsonObject(credentials + body.filterKeys { it != "routing" }), "routing" to body.getValue("routing"), "disabled" to config.flag("disabled"))
                                    }
                                }, checked = if (configured) !config.flag("disabled") else null,
                                onChecked = { vm.write(base + listOf("channel", type, "status"), "PATCH", apiBody("disabled" to !it)) }, actions = if (configured && !channel.flag("configless")) listOf(
                                    MenuAction("断开渠道", Icons.Filled.LinkOff) { confirmation = ConfirmAction("断开消息渠道", channel.text("display_name")) { vm.write(base + listOf("channel", type), "DELETE") } },
                                ) else emptyList())
                        }
                    }
                }
            }
        }
    }
    editor?.let { ManagementForm(it, state.busy, state.error, onDismiss = { editor = null }) }
    state.audio?.let { AudioPreviewDialog(it, vm::dismissAudio) }
    state.dependencyPrompt?.let { prompt -> AlertDialog(onDismissRequest = vm::dismissDependency,
        title = { Text("安装运行依赖") }, text = { Text("启用此 Agent 需要在当前 Bot 的工作空间安装 ${prompt.text("name").ifBlank { prompt.text("dependency") }}。") },
        confirmButton = { MemohActionButton("安装并启用", Icons.Filled.Download, vm::installAgentDependency, primary = true) },
        dismissButton = { TextButton(vm::dismissDependency) { Text("取消") } }) }
    state.operationLog?.let { log -> AlertDialog(onDismissRequest = {}, title = { Text("安装 Agent") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }, confirmButton = {}) }
    confirmation?.let { action -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(action.title) }, text = { Text(action.message) },
        confirmButton = { MemohActionButton("确认", Icons.Filled.Check, { confirmation = null; action.run() }, primary = true) },
        dismissButton = { TextButton({ confirmation = null }) { Text("取消") } }) }
    state.authorization?.let { auth -> AlertDialog(onDismissRequest = vm::cancelAuthorization, title = { Text("连接 Agent") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("在授权页面输入以下代码，完成后会自动更新。")
            Text(auth.text("user_code"), style = MaterialTheme.typography.headlineMedium)
            Text(auth.text("status"), style = MaterialTheme.typography.bodyMedium)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { MemohActionButton("打开授权页", Icons.Filled.OpenInNew, {
        val url = auth.text("verification_url").ifBlank { auth.text("authorization_url") }
        if (url.startsWith("https://")) uriHandler.openUri(url)
    }) }, dismissButton = { TextButton(vm::cancelAuthorization) { Text("取消") } }) }
    state.result?.let { result ->
        val data = result.obj()
        val device = data["device"].obj()
        val url = data.text("authorization_url").ifBlank { data.text("auth_url").ifBlank { device.text("verification_uri") } }
        val command = data.text("command").ifBlank { if (data.text("key").isNotBlank()) vm.runtimeCommand(data) else "" }
        var detailsOpen by remember(result) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = vm::dismiss, title = { Text(if (command.isNotBlank()) "电脑接入命令" else if (url.isNotBlank()) "完成授权" else "操作结果") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (device.text("user_code").isNotBlank()) Text(device.text("user_code"), style = MaterialTheme.typography.headlineMedium)
                if (command.isNotBlank()) Text(command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                else {
                    val description = when {
                        data.flag("has_token") && !data.flag("expired") -> "账号已连接" + data.text("account").takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                        data.flag("expired") -> "授权已过期，请重新连接"
                        url.isNotBlank() -> "前往授权页面完成连接，返回后会检查授权状态。"
                        data.flag("success") || data.flag("ok") -> "测试通过"
                        data.text("message").isNotBlank() -> data.text("message")
                        data.text("text").isNotBlank() -> data.text("text")
                        data.text("token").isNotBlank() -> "关联码 · ${data.text("token")}\n有效期至 ${data.text("expires_at")}"
                        data["imported"] is JsonObject -> "已恢复 " + data["imported"].obj().entries.joinToString("、") { "${it.value.jsonPrimitive.content} 项 ${it.key}" }
                        data.text("status").isNotBlank() -> "状态 · ${data.text("status")}" + data.text("alias").takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
                        else -> "操作已完成，详情中可查看服务端返回结果。"
                    }
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                    TextButton({ detailsOpen = !detailsOpen }) { Text(if (detailsOpen) "收起详情" else "查看详情") }
                    if (detailsOpen) Text(formatted(result), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { if (url.startsWith("https://")) MemohActionButton("打开授权页", Icons.Filled.OpenInNew, { uriHandler.openUri(url) }) else if (command.isNotBlank()) MemohActionButton("复制", Icons.Filled.ContentCopy, {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Memoh 电脑接入命令", command))
        }) else TextButton(vm::dismiss) { Text("完成") } }, dismissButton = { TextButton({ vm.dismiss(); vm.refresh() }) { Text("关闭并刷新") } })
    }
}

@Composable
internal fun ManagementRow(title: String, subtitle: String, icon: ImageVector, index: Int = 0, count: Int = 1, enabled: Boolean = true,
    onClick: (() -> Unit)? = null, actions: List<MenuAction> = emptyList(), checked: Boolean? = null, onChecked: (Boolean) -> Unit = {}) {
    var menuOpen by remember { mutableStateOf(false) }
    SegmentedListItem(onClick ?: { if (checked != null) onChecked(!checked) }, shapes = ListItemDefaults.segmentedShapes(index, count), modifier = Modifier.fillMaxWidth(), enabled = enabled,
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        leadingContent = { MemohListIcon(icon) }, supportingContent = { if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (checked != null) Switch(checked, onChecked, enabled = enabled, thumbContent = { Icon(if (checked) Icons.Filled.Check else Icons.Filled.Close, null, Modifier.size(SwitchDefaults.IconSize)) })
                if (actions.isNotEmpty()) Box {
                    IconButton({ menuOpen = true }, enabled = enabled) { Icon(Icons.Filled.MoreVert, "${title}操作") }
                    MemohPopupMenu(menuOpen, { menuOpen = false }, Alignment.TopEnd) { MemohMenuGroup {
                        actions.forEachIndexed { i, action -> MemohMenuRow(action.label, action.icon, { menuOpen = false; action.run() }, index = i, count = actions.size) }
                    } }
                } else if (checked == null && onClick != null) Icon(Icons.Filled.ChevronRight, null, Modifier.size(20.dp))
            }
        }, contentPadding = PaddingValues(16.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
}

@Composable
internal fun GroupLabel(title: String) { Text(title, Modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable
internal fun ManagementNotice(text: String, error: Boolean = false) {
    Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = MaterialTheme.shapes.large,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Actions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun CatalogAddButton(label: String, options: List<JsonObject>, enabled: Boolean, onSelect: (JsonObject) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MemohActionButton(label, Icons.Filled.Add, { expanded = true }, enabled = enabled)
        MemohPopupMenu(expanded, { expanded = false }, Alignment.TopStart) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                MemohMenuGroup {
                    options.forEachIndexed { index, option ->
                        MemohMenuRow(option.text("name").ifBlank { option.text("display_name").ifBlank { option.text("provider").ifBlank { option.text("id") } } },
                            Icons.Filled.Extension, { expanded = false; onSelect(option) }, index = index, count = options.size)
                    }
                }
            }
        }
    }
}

private fun acpEditor(state: ManagementState, agent: JsonObject, vm: ManagementViewModel): ManagementEditor {
    val metadata = state.bot?.metadata.obj()
    val provider = agent["metadata"].obj().text("provider")
    val profile = state.data["profiles"].objects("items").firstOrNull { it.text("id") == provider }.obj()
    val acp = metadata["acp"].obj()
    val configs = acp["agents"].obj()
    val config = configs[provider].obj()
    val managed = config["managed"].obj()
    val fields = profile["managed_fields"].objects().map { field -> FormField(field.text("id"), field.text("label").ifBlank { field.text("id") },
        if (field.flag("sensitive") || field.text("type") == "password") "" else managed.text(field.text("id")), secret = field.flag("sensitive") || field.text("type") == "password",
        omitBlank = field.flag("sensitive") || field.text("type") == "password") }
    return ManagementEditor("${profile.text("display_name").ifBlank { provider }} 配置", listOf(FormField("setup_mode", "配置方式", config.text("setup_mode").ifBlank { "api_key" },
        choices = (profile["setup_modes"] as? JsonArray)?.map { it.jsonPrimitive.content to it.jsonPrimitive.content }.orEmpty())) + fields) { body, saved ->
        val next = JsonObject(config + mapOf("enabled" to JsonPrimitive(true), "setup_mode" to body.getValue("setup_mode"), "managed" to JsonObject(managed + body.filterKeys { it != "setup_mode" })))
        vm.write(listOf("bots", state.botId), "PUT", apiBody("metadata" to JsonObject(metadata + mapOf("acp" to JsonObject(acp + mapOf("agents" to JsonObject(configs + mapOf(provider to next))))))), onSaved = saved)
    }
}
