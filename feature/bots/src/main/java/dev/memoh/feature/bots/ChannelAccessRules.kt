package dev.memoh.feature.bots

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*

internal fun channelRuleBody(body: JsonObject): JsonObject {
    val identity = body.text("subject") == "identity"
    val target = body.text(if (identity) "channel_identity_id" else "subject_channel_type")
    require(target.isNotBlank()) { "请选择渠道成员或平台" }
    val scope = body.filterKeys { it in setOf("conversation_type", "conversation_id", "thread_id") && body.text(it).isNotBlank() }
    require(scope["thread_id"] == null || scope["conversation_id"] != null) { "指定话题时需要填写所在会话" }
    return apiBody("channel_identity_id" to if (identity) target else JsonNull,
        "subject_channel_type" to if (identity) JsonNull else target,
        "effect" to body.text("effect"), "enabled" to body.flag("enabled"), "description" to body.text("description"),
        "source_scope" to if (scope.isEmpty()) JsonNull else JsonObject(scope))
}

internal fun LazyListScope.channelAccessRules(state: ManagementState, vm: ManagementViewModel,
    editor: (ManagementEditor) -> Unit, confirm: (ConfirmAction) -> Unit) {
    val base = listOf("bots", state.botId, "acl")
    val editable = state.canManageBot && !state.busy
    val identities = state.data["acl-identities"].objects("items")
    val channels = state.data["channels"].objects().filter { it.text("type") != "local" }
    val effects = listOf("allow" to "允许", "deny" to "拒绝")
    fun ruleEditor(rule: JsonObject? = null) {
        val scope = rule?.get("source_scope").obj()
        val fields = listOf(
            FormField("subject", "规则对象", if (rule?.text("subject_channel_type").isNullOrBlank()) "identity" else "channel", choices = listOf("identity" to "指定渠道成员", "channel" to "整个平台")),
            FormField("channel_identity_id", "渠道成员", rule?.text("channel_identity_id").orEmpty(), required = true,
                choices = identities.map { it.text("id") to "${it.text("display_name").ifBlank { it.text("channel_subject_id") }} · ${it.text("channel")}" }, visible = { it["subject"] == "identity" }),
            FormField("subject_channel_type", "平台", rule?.text("subject_channel_type").orEmpty(), required = true,
                choices = channels.map { it.text("type") to it.text("display_name").ifBlank { it.text("type") } }, visible = { it["subject"] == "channel" }),
            FormField("effect", "处理方式", rule?.text("effect") ?: "allow", choices = effects),
            FormField("enabled", "启用规则", (rule?.flag("enabled", true) ?: true).toString(), FieldKind.Toggle),
            FormField("description", "说明", rule?.text("description").orEmpty()),
            FormField("conversation_type", "会话范围", scope.text("conversation_type"), choices = listOf("" to "所有会话", "private" to "私聊", "group" to "群聊", "thread" to "话题")),
            FormField("conversation_id", "指定会话 ID（可选）", scope.text("conversation_id")),
            FormField("thread_id", "指定话题 ID（可选）", scope.text("thread_id")),
        )
        editor(ManagementEditor(if (rule == null) "添加访问规则" else "编辑访问规则", fields) { body, saved ->
            vm.write(base + "rules" + (rule?.text("id")?.let { listOf(it) } ?: emptyList()), if (rule == null) "POST" else "PUT", channelRuleBody(body), onSaved = saved)
        })
    }
    if ("acl-default" in state.data) {
        val default = state.data["acl-default"].obj().text("default_effect")
        item { GroupLabel("平台访问控制") }
        item { ManagementRow("访问模式", if (default == "deny") "白名单：仅允许名单中的成员" else "黑名单：拒绝名单中的成员", Icons.Filled.Shield,
            enabled = editable, onClick = {
                editor(ManagementEditor("平台访问模式", listOf(FormField("default_effect", "模式", default, choices = listOf("allow" to "黑名单模式", "deny" to "白名单模式")))) { body, saved ->
                    vm.write(base + "default-effect", "PUT", body, onSaved = saved)
                })
            }) }
    }
    if ("acl-rules" in state.data) {
        item { Actions { MemohActionButton("添加访问规则", Icons.Filled.Add, { ruleEditor() }, enabled = editable, primary = true) } }
        val rules = state.data["acl-rules"].objects("items")
        itemsIndexed(rules, key = { _, rule -> "acl:${rule.text("id")}" }) { index, rule ->
            val name = rule.text("channel_identity_display_name").ifBlank { rule.text("channel_subject_id").ifBlank { rule.text("subject_channel_type") } }
            val label = name.ifBlank { "所有渠道" }
            val details = listOf(if (rule.text("effect") == "allow") "允许" else "拒绝", rule.text("source_conversation_name"), rule.text("description")).filter(String::isNotBlank).joinToString(" · ")
            ManagementRow(label, details, Icons.Filled.Forum, index, rules.size, editable, onClick = { ruleEditor(rule) },
                checked = rule.flag("enabled", true), onChecked = { enabled ->
                    val body = JsonObject(rule.filterKeys { it in setOf("channel_identity_id", "subject_channel_type", "source_scope", "description", "effect") } + apiBody("enabled" to enabled))
                    vm.write(base + listOf("rules", rule.text("id")), "PUT", body)
                }, actions = listOf(MenuAction("删除规则", Icons.Filled.Delete) {
                    confirm(ConfirmAction("删除访问规则", "删除 $label 的这条规则。") { vm.write(base + listOf("rules", rule.text("id")), "DELETE") })
                }))
        }
        if (rules.isEmpty()) item { ManagementNotice("尚未添加规则，平台成员按当前访问模式处理。") }
    }
    state.data["acl-rules-error"]?.let { error -> item { ManagementNotice((error as? JsonPrimitive)?.content.orEmpty()) } }
}
