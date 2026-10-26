package dev.memoh.feature.bots

import dev.memoh.core.model.ChatModel
import kotlinx.serialization.json.*

private val modelJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
internal fun managementChatModel(value: JsonObject): ChatModel? =
    runCatching { modelJson.decodeFromJsonElement(ChatModel.serializer(), value) }.getOrNull()

private val legacyConfigKeys = setOf("thinking_mode", "reasoning_efforts", "compatibilities", "reasoning_dialect",
    "reasoning_off_support", "reasoning_default_on", "thinking_budget_min", "thinking_budget_max")
private val modelConfigFields = setOf("schema_version", "context_window", "max_output_tokens", "dimensions", "description",
    "modalities", "tool_call", "structured_output", "reasoning")
private val reasoningTiers = listOf("minimal", "low", "medium", "high", "xhigh", "max")
private fun JsonElement?.strings() = (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun strings(values: List<String>) = JsonArray(values.distinct().map(::JsonPrimitive))

/** Read old servers' declarations; current servers already supply canonical v2 alongside a legacy view. */
internal fun editableModelConfig(raw: JsonObject): JsonObject {
    val config = raw.filterKeys { it !in legacyConfigKeys }.toMutableMap()
    if ((raw["schema_version"] as? JsonPrimitive)?.intOrNull != 2) {
        val capabilities = raw["compatibilities"].strings()
        config["modalities"] = buildJsonObject {
            put("input", strings(listOf("text") + listOfNotNull("image".takeIf { "vision" in capabilities }, "pdf".takeIf { "file-input" in capabilities })))
            put("output", strings(listOf("text") + listOfNotNull("image".takeIf { "image-output" in capabilities })))
        }
        if ("tool-call" in capabilities) config["tool_call"] = JsonPrimitive(true)
        if (raw.text("thinking_mode") != "none" && ("reasoning" in capabilities || raw.text("thinking_mode") in listOf("toggle", "adaptive", "only_adaptive", "always"))) {
            val efforts = raw["reasoning_efforts"].strings()
            config["reasoning"] = buildJsonObject {
                if (raw.text("thinking_mode") != "always") {
                    val tiers = reasoningTiers.filter { it in efforts }.ifEmpty {
                        if ("enabled" in efforts || raw["thinking_budget_min"] != null || raw["thinking_budget_max"] != null) emptyList()
                        else listOf("low", "medium", "high")
                    }
                    if (tiers.isNotEmpty()) put("efforts", strings(tiers))
                    if (efforts.any { it in listOf("enabled", "disable", "none") } || raw.text("thinking_mode") == "toggle") put("toggle", true)
                    val dialect = if (raw.text("thinking_mode") in listOf("adaptive", "only_adaptive")) "adaptive" else raw.text("reasoning_dialect")
                    if (dialect.isNotBlank()) put("dialect", dialect)
                    if (raw["thinking_budget_min"] != null || raw["thinking_budget_max"] != null) put("budget", buildJsonObject {
                        raw["thinking_budget_min"]?.let { put("min", it) }; raw["thinking_budget_max"]?.let { put("max", it) }
                    })
                }
                raw["reasoning_default_on"]?.let { put("default", buildJsonObject { put("enabled", it) }) }
                raw.text("reasoning_off_support").takeIf(String::isNotBlank)?.let { put("off", it) }
            }
        }
    }
    config["schema_version"] = JsonPrimitive(2)
    return JsonObject(config)
}

internal fun modelFormBody(body: JsonObject, existing: JsonObject = JsonObject(emptyMap())): JsonObject {
    val previous = if ("config" in body) existing.filterKeys { it in modelConfigFields || it in legacyConfigKeys } else existing
    val raw = (previous + body["config"].obj() + body.filterKeys { it in legacyConfigKeys || it in setOf("context_window", "max_output_tokens", "dimensions", "description") }).toMutableMap()
    raw.entries.removeAll { it.value == JsonNull }
    val config = editableModelConfig(JsonObject(raw)).toMutableMap()
    if (body.text("type") == "embedding") {
        listOf("modalities", "tool_call", "structured_output", "reasoning", "context_window", "max_output_tokens").forEach(config::remove)
    } else {
        config.remove("dimensions")
        val modalities = config["modalities"].obj()
        config["modalities"] = buildJsonObject {
            put("input", strings(listOf("text") + (body["_input"] ?: modalities["input"]).strings()))
            put("output", strings((body["_output"] ?: modalities["output"]).strings().ifEmpty { if (body["_output"] == null) listOf("text") else emptyList() }))
        }
        listOf("tool_call", "structured_output").forEach { key -> body[key]?.let { if (it == JsonPrimitive(true)) config[key] = it else config.remove(key) } }
        val legacyDisabled = body["compatibilities"] != null && "reasoning" !in body["compatibilities"].strings()
        val enabled = body["_reasoning"]?.let { it == JsonPrimitive(true) } ?: (!legacyDisabled && config["reasoning"] is JsonObject)
        if (!enabled) config.remove("reasoning") else {
            val reasoning = config["reasoning"].obj().toMutableMap()
            body["_efforts"]?.let { value ->
                val efforts = reasoningTiers.filter { it in value.strings() }
                if (efforts.isEmpty()) reasoning.remove("efforts") else reasoning["efforts"] = strings(efforts)
            }
            body["_toggle"]?.let { if (it == JsonPrimitive(true)) reasoning["toggle"] = it else reasoning.remove("toggle") }
            mapOf("_dialect" to "dialect", "_off" to "off").forEach { (field, key) ->
                body[field]?.let { if (body.text(field).isBlank()) reasoning.remove(key) else reasoning[key] = it }
            }
            val budget = reasoning["budget"].obj().toMutableMap()
            mapOf("_budget_min" to "min", "_budget_max" to "max").forEach { (field, key) -> body[field]?.let {
                if (it == JsonNull) budget.remove(key) else budget[key] = it
            } }
            val min = (budget["min"] as? JsonPrimitive)?.longOrNull
            val max = (budget["max"] as? JsonPrimitive)?.longOrNull
            require(min == null || min >= 0) { "推理预算下限不能小于 0" }
            require(max == null || max > 0) { "推理预算上限必须大于 0" }
            require(min == null || max == null || min <= max) { "推理预算下限不能大于上限" }
            if (budget.isEmpty()) reasoning.remove("budget") else reasoning["budget"] = JsonObject(budget)
            val defaults = reasoning["default"].obj().toMutableMap()
            body["_default_on"]?.let { when (body.text("_default_on")) {
                "on" -> defaults["enabled"] = JsonPrimitive(true); "off" -> defaults["enabled"] = JsonPrimitive(false); else -> defaults.remove("enabled")
            } }
            body["_default_effort"]?.let { if (body.text("_default_effort") in reasoning["efforts"].strings()) defaults["effort"] = it else defaults.remove("effort") }
            if ("_efforts" in body && defaults["effort"] != null && (defaults["effort"] as? JsonPrimitive)?.content !in reasoning["efforts"].strings()) defaults.remove("effort")
            if (defaults.isEmpty()) reasoning.remove("default") else reasoning["default"] = JsonObject(defaults)
            body["_replay"]?.let { if (body.text("_replay").isBlank()) reasoning.remove("replay") else reasoning["replay"] = buildJsonObject {
                put("field", it); if (body.flag("_replay_required")) put("required", true)
            } }
            config["reasoning"] = JsonObject(reasoning)
        }
    }
    return JsonObject(body.filterKeys { it in setOf("name", "model_id", "provider_id", "type", "enable") } + ("config" to JsonObject(config)))
}

internal fun modelFields(model: JsonObject?, providers: List<JsonObject>): List<FormField> {
    val config = editableModelConfig(model?.get("config").obj())
    val reasoning = config["reasoning"].obj()
    val defaults = reasoning["default"].obj()
    val modalities = config["modalities"].obj()
    val chat: (Map<String, String>) -> Boolean = { it["type"] == "chat" }
    val thinking: (Map<String, String>) -> Boolean = { chat(it) && it["_reasoning"] == "true" }
    val advanced: (Map<String, String>) -> Boolean = { thinking(it) && it["_advanced_reasoning"] == "true" }
    return listOf(
        FormField("name", "显示名称", model?.text("name").orEmpty()),
        FormField("model_id", "模型 ID", model?.text("model_id").orEmpty(), required = true),
        FormField("provider_id", "供应商", model?.text("provider_id").orEmpty(), choices = providers.map { it.text("id") to it.text("name") }, required = true),
        FormField("type", "模型类型", model?.text("type") ?: "chat", choices = listOf("chat" to "对话", "embedding" to "Embedding")),
        FormField("enable", "启用", (model?.flag("enable", true) ?: true).toString(), FieldKind.Toggle),
        FormField("context_window", "上下文窗口（tokens）", config.text("context_window"), FieldKind.Number, min = 1, visible = chat),
        FormField("max_output_tokens", "最大输出（tokens）", config.text("max_output_tokens"), FieldKind.Number, min = 1, visible = chat),
        FormField("dimensions", "向量维度", config.text("dimensions"), FieldKind.Number, min = 1, visible = { it["type"] == "embedding" }),
        FormField("_input", "输入", (modalities["input"] ?: strings(listOf("text"))).toString(), FieldKind.StringSet,
            choices = listOf("text" to "文字", "image" to "图片", "audio" to "音频", "video" to "视频", "pdf" to "PDF"), visible = chat, fixedChoices = setOf("text")),
        FormField("_output", "输出", (modalities["output"] ?: strings(listOf("text"))).toString(), FieldKind.StringSet,
            choices = listOf("text" to "文字", "image" to "图片", "audio" to "音频"), visible = chat),
        FormField("tool_call", "工具调用", config.flag("tool_call").toString(), FieldKind.Toggle, visible = chat),
        FormField("structured_output", "结构化输出", config.flag("structured_output").toString(), FieldKind.Toggle, visible = chat),
        FormField("_reasoning", "推理能力", (config["reasoning"] is JsonObject).toString(), FieldKind.Toggle, visible = chat),
        FormField("_efforts", "思考强度", (reasoning["efforts"] ?: strings(if (config["reasoning"] is JsonObject) emptyList() else listOf("low", "medium", "high"))).toString(), FieldKind.StringSet,
            choices = reasoningTiers.map { it to dev.memoh.core.model.reasoningEffortLabel(it) }, visible = thinking),
        FormField("_toggle", "支持推理开关", reasoning.flag("toggle").toString(), FieldKind.Toggle, visible = thinking),
        FormField("_advanced_reasoning", "高级推理选项", "false", FieldKind.Disclosure, visible = thinking),
        FormField("_dialect", "推理协议", reasoning.text("dialect"), choices = listOf("" to "未声明", "tier" to "强度等级", "budget" to "Token 预算", "toggle" to "开关", "adaptive" to "自适应"), visible = advanced),
        FormField("_off", "关闭推理时", reasoning.text("off"), choices = listOf("" to "未声明", "accepted" to "接受关闭", "low_effort_only" to "仅降至低强度", "rejected" to "不支持关闭"), visible = advanced),
        FormField("_default_on", "默认推理状态", when ((defaults["enabled"] as? JsonPrimitive)?.booleanOrNull) { true -> "on"; false -> "off"; null -> "" },
            choices = listOf("" to "未声明", "on" to "开启", "off" to "关闭"), visible = advanced),
        FormField("_default_effort", "默认思考强度", defaults.text("effort"), choicesFor = { values -> listOf("" to "未声明") +
            (runCatching { Json.parseToJsonElement(values["_efforts"].orEmpty()) }.getOrNull()).strings().map { it to dev.memoh.core.model.reasoningEffortLabel(it) } }, visible = advanced),
        FormField("_budget_min", "推理预算下限", reasoning["budget"].obj().text("min"), FieldKind.Number, min = 0, visible = advanced),
        FormField("_budget_max", "推理预算上限", reasoning["budget"].obj().text("max"), FieldKind.Number, min = 1, visible = advanced),
        FormField("_replay", "推理回传字段", reasoning["replay"].obj().text("field"), choices = listOf("" to "默认") + listOf("reasoning_content", "reasoning_details", "reasoning").map { it to it }, visible = advanced),
        FormField("_replay_required", "工具回复需回传推理", reasoning["replay"].obj().flag("required").toString(), FieldKind.Toggle, visible = advanced),
        FormField("description", "说明", config.text("description"), multiline = true),
        FormField("_advanced_config", "其他高级配置", "false", FieldKind.Disclosure),
        FormField("config", "高级配置", JsonObject(config.filterKeys { it !in modelConfigFields }).toString(), FieldKind.Object, multiline = true, visible = { it["_advanced_config"] == "true" }),
    )
}

internal fun modelProducesImage(model: JsonObject): Boolean {
    val config = model["config"].obj()
    return "image" in config["modalities"].obj()["output"].strings() ||
        config["schema_version"] != JsonPrimitive(2) && "image-output" in config["compatibilities"].strings()
}

/** The server's config version selects the write format; stable v0.22 servers still read v1. */
internal fun useModelConfigV2(model: JsonObject?, models: List<JsonObject>, catalog: JsonObject?): Boolean {
    fun v2(value: JsonObject) = (value["config"].obj()["schema_version"] as? JsonPrimitive)?.intOrNull == 2
    return if (model != null) v2(model) else models.any(::v2) || catalog?.get("models").objects().any(::v2)
}

internal fun legacyModelFields(model: JsonObject?, providers: List<JsonObject>): List<FormField> {
    val config = model?.get("config").obj()
    val keys = setOf("context_window", "max_output_tokens", "thinking_mode", "reasoning_efforts", "compatibilities", "description", "dimensions")
    return modelFields(model, providers).filter { it.key in setOf("name", "model_id", "provider_id", "type", "enable", "context_window", "max_output_tokens", "dimensions") } + listOf(
        FormField("thinking_mode", "思考机制", config.text("thinking_mode").let { if (it == "only_adaptive") "adaptive" else it },
            choices = listOf("" to "由模型目录声明", "adaptive" to "自适应", "toggle" to "可开关", "always" to "始终开启", "none" to "无思考能力"), visible = { it["type"] == "chat" }),
        FormField("reasoning_efforts", "支持的思考强度", config["reasoning_efforts"]?.toString() ?: "[]", FieldKind.StringSet,
            choices = listOf("disable", "minimal", "low", "medium", "high", "xhigh", "max").map { it to dev.memoh.core.model.reasoningEffortLabel(it) }, visible = { it["type"] == "chat" }),
        FormField("compatibilities", "模型能力", config["compatibilities"]?.toString() ?: "[]", FieldKind.StringSet,
            choices = listOf("vision" to "识别图片", "tool-call" to "工具调用", "image-output" to "生成图片", "reasoning" to "推理"), visible = { it["type"] == "chat" }),
        FormField("description", "说明", config.text("description"), multiline = true),
        FormField("config", "高级模型能力", JsonObject(config.filterKeys { it !in keys }).toString(), FieldKind.Object, multiline = true),
    )
}

internal fun legacyModelFormBody(body: JsonObject): JsonObject {
    val keys = setOf("context_window", "max_output_tokens", "thinking_mode", "reasoning_efforts", "compatibilities", "description", "dimensions")
    val config = (body["config"].obj() + body.filterKeys { it in keys && body[it] != JsonNull }).toMutableMap()
    keys.filter { body[it] == JsonNull }.forEach(config::remove)
    if (body.text("type") == "embedding") (keys - setOf("description", "dimensions")).forEach(config::remove)
    else {
        config.remove("dimensions")
        if (JsonPrimitive("reasoning") !in (config["compatibilities"] as? JsonArray).orEmpty()) {
            config.remove("thinking_mode"); config.remove("reasoning_efforts")
        } else if (config["thinking_mode"] in listOf(JsonPrimitive("none"), JsonPrimitive("always"))) config.remove("reasoning_efforts")
        else {
            if (config.textValue("thinking_mode").isBlank()) config.remove("thinking_mode")
            val efforts = config["reasoning_efforts"].strings().map { if (it == "none") "disable" else it }.filterNot { it == "adaptive" }.distinct()
            if (efforts.isEmpty()) config.remove("reasoning_efforts") else config["reasoning_efforts"] = strings(efforts)
        }
    }
    return JsonObject(body.filterKeys { it !in keys && it != "config" } + ("config" to JsonObject(config)))
}

private fun Map<String, JsonElement>.textValue(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
