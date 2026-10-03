package dev.memoh.feature.bots

import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*
import java.time.ZoneId

internal fun agentEditor(state: ManagementState, agent: JsonObject?, vm: ManagementViewModel): ManagementEditor {
    val metadata = agent?.get("metadata").obj()
    val provider = metadata.text("provider").ifBlank { agent?.text("runtime") ?: "codex" }
    val profiles = state.data["profiles"].objects("items")
    val providers = listOf("codex" to "Codex", "claude-code" to "Claude Code") + profiles
        .filter { it.text("id") !in listOf("codex", "claude-code") }.map { it.text("id") to it.text("display_name") }
    val id = agent?.text("id").orEmpty()
    if (id.isNotBlank()) vm.agentOptions(id)
    fun catalog() = vm.state.value.agentOptions[id]?.get("catalog").obj()
    fun direct(values: Map<String, String>) = values["provider"] in listOf("codex", "claude-code")
    val fields = listOf(
        FormField("name", "名称", agent?.text("name").orEmpty(), required = true),
        FormField("provider", "Agent", provider, choices = if (agent == null) providers else listOf(provider to (providers.firstOrNull { it.first == provider }?.second ?: provider))),
        FormField("auth", "认证方式", metadata.text("auth").ifBlank { if (provider == "codex") "chatgpt" else "workspace" },
            choicesFor = { values -> if (values["provider"] == "codex") listOf("chatgpt" to "ChatGPT 账号", "api_key" to "API key")
                else listOf("workspace" to "工作空间已有登录", "oauth_token" to "账号 token", "api_key" to "API key") }, visible = ::direct),
        FormField("base_url", "API 地址（空白使用默认）", metadata.text("base_url"), visible = ::direct),
        FormField("model", "默认模型", metadata.text("model"), choicesFor = {
            listOf("" to "Agent 默认") + catalog()["models"].objects().map { it.text("id") to it.text("name").ifBlank { it.text("id") } }
        }, visible = ::direct),
        FormField("reasoning_effort", "思考强度", metadata.text("reasoning_effort"), choicesFor = { values ->
            val model = catalog()["models"].objects().firstOrNull { it.text("id") == values["model"] }.obj()
            listOf("" to "模型默认") + model["reasoning_efforts"].objects().map { it.text("id") to it.text("name").ifBlank { it.text("id") } }
        }, visible = { it["provider"] == "codex" }),
        FormField("permission_mode", "执行权限", metadata.text("permission_mode"), choicesFor = {
            listOf("" to "Agent 默认") + vm.state.value.agentOptions[id]?.get("controls").obj()["modes"].obj()["available_modes"].objects()
                .map { it.text("id") to it.text("name").ifBlank { it.text("id") } }
        }, visible = ::direct),
    )
    return ManagementEditor(if (agent == null) "添加 Agent" else "运行配置", fields) { body, saved ->
        val selected = body.text("provider")
        val direct = selected in listOf("codex", "claude-code")
        val next = metadata.toMutableMap().apply {
            put("provider", JsonPrimitive(selected))
            if (direct) {
                val authChoices = if (selected == "codex") setOf("chatgpt", "api_key") else setOf("workspace", "api_key", "oauth_token")
                put("auth", JsonPrimitive(body.text("auth").takeIf { it in authChoices } ?: if (selected == "codex") "chatgpt" else "workspace"))
                listOf("base_url", "model", "permission_mode", "reasoning_effort").forEach { key ->
                    if (body.text(key).isBlank()) remove(key) else put(key, body.getValue(key))
                }
            }
        }
        val payload = apiBody("name" to body.text("name"), "metadata" to JsonObject(next))
        vm.write(if (agent == null) listOf("bots", state.botId, "agents") else listOf("bots", state.botId, "agents", id),
            if (agent == null) "POST" else "PATCH",
            if (agent == null) JsonObject(payload + apiBody("runtime" to if (direct) selected else "acp", "enabled" to false)) else payload,
            onSaved = saved)
    }
}

internal fun modelUpdate(model: JsonObject, enabled: Boolean): JsonObject =
    JsonObject(model.filterKeys { it in setOf("name", "model_id", "provider_id", "type", "config") } + ("enable" to JsonPrimitive(enabled)))

internal fun schemaFields(schema: JsonObject, current: JsonObject, configured: Boolean): List<FormField> =
    schema.entries.map { (key, value) ->
        val field = value.obj()
        val secret = field.text("type") == "password" || field.flag("secret") || key.contains("api_key") || key.contains("token") || key.contains("secret")
        val kind = when (field.text("type")) { "boolean" -> FieldKind.Toggle; "integer" -> FieldKind.Number; "number" -> FieldKind.Decimal; "object" -> FieldKind.Object; "array" -> FieldKind.Array; else -> FieldKind.Text }
        FormField(key, field.text("title").ifBlank { field.text("label").ifBlank { key } },
            if (secret) "" else if (kind in listOf(FieldKind.Object, FieldKind.Array)) current[key]?.toString().orEmpty() else current.text(key).ifBlank {
                field.text("default_value").ifBlank { field.text("default") }.ifBlank { if (kind == FieldKind.Toggle) "false" else "" }
            }, kind, choices = (field["enum"] as? JsonArray)?.map { it.jsonPrimitive.content to it.jsonPrimitive.content }.orEmpty(),
            multiline = kind in listOf(FieldKind.Object, FieldKind.Array) || field.text("type") == "textarea" || field.flag("multiline"), secret = secret,
            required = field.flag("required") && !configured, omitBlank = secret && configured)
    }

internal fun approvalEditor(state: ManagementState, vm: ManagementViewModel): ManagementEditor {
    val settings = state.data["settings"].obj()
    val config = settings["tool_approval_config"].obj()
    val fields = mutableListOf(FormField("acl_default_effect", "渠道默认访问", settings.text("acl_default_effect").ifBlank { "allow" }, choices = listOf("allow" to "允许", "deny" to "拒绝")),
        FormField("persist_full_tool_results", "保留完整工具结果", settings.flag("persist_full_tool_results").toString(), FieldKind.Toggle),
        FormField("show_tool_calls_in_im", "在消息渠道显示工具调用", settings.flag("show_tool_calls_in_im").toString(), FieldKind.Toggle),
        FormField("enabled", "启用工具审批", config.flag("enabled").toString(), FieldKind.Toggle))
    for ((key, title) in listOf("read" to "读取文件", "write" to "修改文件", "exec" to "执行命令")) {
        val policy = config[key].obj()
        val mode = policy.text("mode").ifBlank { if (policy.flag("require_approval", key == "write")) "ask" else "allow" }
        fields += FormField("$key.mode", title, mode, choices = listOf("allow" to "直接允许", "ask" to "需要审批", "deny" to "拒绝"))
        for ((suffix, label) in if (key == "exec") listOf("bypass_commands" to "免审批命令", "force_review_commands" to "总是审批的命令")
            else listOf("bypass_globs" to "免审批路径", "force_review_globs" to "总是审批的路径")) {
            val defaults = if (key == "write" && suffix == "bypass_globs") listOf("/data/**", "/tmp/**") else emptyList()
            val lines = (policy[suffix] as? JsonArray)?.map { it.jsonPrimitive.content } ?: defaults
            fields += FormField("$key.$suffix", "$title · $label（每行一项）", lines.joinToString("\n"), multiline = true)
        }
    }
    return ManagementEditor("工具与审批", fields) { body, saved ->
        val policies = listOf("read", "write", "exec").associateWith { key ->
            JsonObject(config[key].obj() + buildMap {
                val mode = body.text("$key.mode")
                put("mode", JsonPrimitive(mode)); put("require_approval", JsonPrimitive(mode == "ask"))
                val names = if (key == "exec") listOf("bypass_commands", "force_review_commands") else listOf("bypass_globs", "force_review_globs")
                names.forEach { suffix -> put(suffix, JsonArray(body.text("$key.$suffix").lines().map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive))) }
            })
        }
        vm.write(listOf("bots", state.botId, "settings"), "PUT", JsonObject(body.filterKeys { it in listOf("acl_default_effect", "persist_full_tool_results", "show_tool_calls_in_im") } +
            mapOf("tool_approval_config" to JsonObject(config + policies + mapOf("enabled" to body.getValue("enabled"))))), onSaved = saved)
    }
}

internal fun validTimezone(body: JsonObject): JsonObject {
    body.text("timezone").takeIf { it.isNotBlank() }?.let { ZoneId.of(it) }
    return body
}
