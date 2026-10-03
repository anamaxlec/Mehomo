package dev.memoh.feature.bots

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*

private val permissions = listOf("chat" to "对话", "workspace_read" to "读取文件", "workspace_write" to "修改文件", "workspace_exec" to "执行命令", "manage" to "管理 Bot")

internal fun memberLabel(id: String, users: List<JsonObject>, fallback: String = id): String = users.asSequence()
    .filter { it.text("id") == id }.map { it.text("display_name").ifBlank { it.text("username").ifBlank { it.text("email") } } }
    .firstOrNull(String::isNotBlank) ?: fallback.ifBlank { "未命名成员" }

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.managementExtras(state: ManagementState, vm: ManagementViewModel,
    showEditor: (ManagementEditor) -> Unit, confirm: (ConfirmAction) -> Unit) {
    val base = listOf("bots", state.botId)
    val editable = state.canManage && !state.busy
    fun edit(title: String, fields: List<FormField>, path: List<String>, method: String = "PUT", result: Boolean = false, transform: (JsonObject) -> JsonObject = { it }) {
        showEditor(ManagementEditor(title, fields) { body, saved -> vm.write(path, method, transform(body), result = result, onSaved = saved) })
    }
    fun sectionError(key: String) { state.data["$key-error"]?.let { item { ManagementNotice((it as? JsonPrimitive)?.content.orEmpty(), true) } } }
    when (state.page) {
        ManagementPage.Workspace -> {
            val container = state.data["container"].obj()
            val running = container.flag("task_running")
            val exists = container.text("container_id").isNotBlank()
            item { GroupLabel("云端工作空间") }
            item { ManagementRow(if (exists) if (running) "运行中" else "已停止" else "尚未创建", container.text("image"), Icons.Filled.Storage,
                onClick = { vm.showResult(container) }) }
            sectionError("container")
            item { Actions {
                if (exists) {
                    MemohActionButton(if (running) "停止" else "启动", if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, {
                        if (running) confirm(ConfirmAction("停止工作空间", "正在执行的任务和连接会被中断。") { vm.write(base + listOf("container", "stop"), "POST") })
                        else vm.write(base + listOf("container", "start"), "POST")
                    }, enabled = editable, primary = true)
                    MemohActionButton("删除", Icons.Filled.Delete, {
                        showEditor(ManagementEditor("删除工作空间", listOf(FormField("preserve_data", "保留 /data 以便重建后恢复", "true", FieldKind.Toggle))) { body, saved ->
                            val preserve = body.flag("preserve_data")
                            confirm(ConfirmAction("删除工作空间", if (preserve) "当前工作空间将停止并删除，/data 会先导出保留。" else "当前工作空间及其中的文件会被删除。") { vm.removeWorkspace(preserve, saved) })
                        })
                    }, enabled = editable, danger = true)
                } else MemohActionButton("创建工作空间", Icons.Filled.Add, {
                    edit("创建工作空间", listOf(FormField("image", "镜像（留空使用默认）", omitBlank = true), FormField("restore_data", "恢复保留的 /data", container.flag("has_preserved_data").toString(), FieldKind.Toggle)), base + "container", "POST")
                }, enabled = editable, primary = true)
                if (container.flag("has_preserved_data") && running) MemohActionButton("恢复文件", Icons.Filled.Restore, {
                    confirm(ConfirmAction("恢复保留文件", "保留的 /data 将恢复到当前工作空间，可能覆盖同名文件。") { vm.write(base + listOf("container", "data", "restore"), "POST") })
                }, enabled = editable)
            } }
            val metrics = state.data["metrics"].obj()
            if (metrics.flag("supported")) {
                item { GroupLabel("资源使用") }
                val values = metrics["metrics"].obj()
                val usage = listOf("CPU" to "${values["cpu"].obj().text("usage_percent")}%", "内存" to sizeText(values["memory"].obj().text("usage_bytes")), "存储" to sizeText(values["storage"].obj().text("used_bytes")))
                itemsIndexed(usage) { index, entry -> ManagementRow(entry.first, entry.second, Icons.Filled.Speed, index, usage.size) }
                val limits = metrics["resource_limits"].obj()
                val desired = limits["desired"].obj()
                val capabilities = limits["capabilities"].obj()
                val fields = listOf("cpu" to FormField("cpu_millicores", "CPU（毫核，0 为不限）", desired.text("cpu_millicores"), FieldKind.Number, min = 0, omitBlank = true),
                    "memory" to FormField("memory_bytes", "内存限制（bytes，0 为不限）", desired.text("memory_bytes"), FieldKind.Number, min = 0, omitBlank = true),
                    "storage" to FormField("storage_bytes", "存储限制（bytes，0 为不限）", desired.text("storage_bytes"), FieldKind.Number, min = 0, omitBlank = true))
                    .filter { capabilities[it.first].obj().flag("hard_limit_supported") || capabilities[it.first].obj().flag("soft_limit_supported") }.map { it.second }
                if (fields.isNotEmpty()) item { ManagementRow("资源限制", if (limits.flag("requires_recreate")) "部分限制需要重建后生效" else "设置当前工作空间的资源额度", Icons.Filled.Tune,
                    enabled = editable, onClick = { edit("资源限制", fields, base + listOf("container", "metrics")) { apiBody("resource_limits" to it) } }) }
            } else if (metrics.text("unsupported_reason").isNotBlank()) item { ManagementNotice("当前工作空间暂不提供资源用量统计。") }
            sectionError("metrics")
            if (state.data["capabilities"].obj().flag("snapshot_supported", true)) {
                item { GroupLabel("快照") }
                if (exists) item { Actions { MemohActionButton("创建快照", Icons.Filled.AddAPhoto, {
                    edit("创建快照", listOf(FormField("snapshot_name", "名称", required = true)), base + listOf("container", "snapshots"), "POST")
                }, enabled = editable && running, primary = true) } }
                val snapshots = state.data["snapshots"].objects("snapshots")
                itemsIndexed(snapshots) { index, snapshot -> ManagementRow(snapshot.text("display_name").ifBlank { snapshot.text("name") }, managementTimestamp(snapshot.text("created_at")), Icons.Filled.History, index, snapshots.size,
                    onClick = { vm.showResult(snapshot) }, actions = if (snapshot.text("version").isNotBlank()) listOf(MenuAction("恢复到此快照", Icons.Filled.Restore) {
                        confirm(ConfirmAction("恢复快照", "工作空间将恢复到 ${snapshot.text("display_name").ifBlank { snapshot.text("name") }} 的状态，之后的修改可能丢失。") { vm.write(base + listOf("container", "snapshots", "rollback"), "POST", apiBody("version" to snapshot["version"])) })
                    }) else emptyList(), enabled = editable) }
                if (snapshots.isEmpty()) item { ManagementNotice("尚无可用快照。") }
                sectionError("snapshots")
            }
            val settings = state.data["settings"].obj()
            if (state.data.containsKey("settings")) item { GroupLabel("桌面") }
            if (state.data.containsKey("settings")) item { ManagementRow("启用云端桌面", state.data["display"].obj().text("message"), Icons.Filled.DesktopWindows,
                enabled = editable, checked = settings.flag("display_enabled"), onChecked = { vm.write(base + "settings", "PUT", apiBody("display_enabled" to it)) }) }
        }
        ManagementPage.Access -> {
            val grants = state.data["grants"].objects("items")
            val candidates = state.data["candidates"].objects("items")
            val users = listOf(state.data["access-current-user"].obj()) + state.data["access-members"].objects("members").map { JsonObject(it + ("id" to JsonPrimitive(it.text("user_id")))) } + candidates
            fun grantEditor(grant: JsonObject? = null) {
                val fields = if (grant == null) listOf(FormField("subject_type", "成员范围", "user", choices = listOf("user" to "指定成员", "everyone" to "所有用户")),
                    FormField("user_id", "成员", choices = candidates.map { candidate -> candidate.text("id") to memberLabel(candidate.text("id"), users) },
                        required = true, visible = { it["subject_type"] == "user" })) else emptyList()
                edit(if (grant == null) "添加成员" else "修改成员权限", fields + FormField("permissions", "允许的操作", grant?.get("permissions")?.toString() ?: "[\"chat\"]", FieldKind.StringSet, choices = permissions),
                    base + "user-access" + (grant?.text("id")?.let { listOf(it) } ?: emptyList()), if (grant == null) "POST" else "PUT")
            }
            item { Actions { MemohActionButton("添加成员", Icons.Filled.PersonAdd, { grantEditor() }, enabled = editable, primary = true) } }
            item { GroupLabel("Bot 成员") }
            itemsIndexed(grants) { index, grant ->
                val owner = grant.flag("is_owner")
                val name = if (grant.text("subject_type") == "everyone") "所有用户" else grant.text("user_display_name").ifBlank { memberLabel(grant.text("user_id"), users, grant.text("user_username").ifBlank { grant.text("user_id") }) }
                val selected = grant["permissions"] as? JsonArray ?: JsonArray(emptyList())
                ManagementRow(name, if (owner) "所有者" else permissions.filter { JsonPrimitive(it.first) in selected }.joinToString("、") { it.second }.ifBlank { "无权限" }, Icons.Filled.Person,
                    index, grants.size, enabled = !state.busy, onClick = if (!owner && editable) ({ grantEditor(grant) }) else null,
                    actions = if (!owner && editable) listOf(MenuAction("移除成员", Icons.Filled.PersonRemove) {
                        confirm(ConfirmAction("移除成员", "$name 将失去通过此授权访问 Bot 的权限。") { vm.write(base + listOf("user-access", grant.text("id")), "DELETE") })
                    }) else emptyList())
            }
            val managers = state.data["channel-managers"].objects("items")
            if (managers.isNotEmpty()) item { GroupLabel("渠道管理权限") }
            itemsIndexed(managers) { index, identity -> ManagementRow(identity.text("channel_identity_display_name").ifBlank { identity.text("channel_subject_id") }, identity.text("channel_type") + if (identity.flag("inherited")) " · 继承成员授权" else "",
                Icons.Filled.Forum, index, managers.size, enabled = editable, checked = identity.flag("manage"), onChecked = { granted ->
                    vm.write(base + "channel-managers", "POST", apiBody("channel_identity_id" to identity.text("channel_identity_id"), "granted" to granted))
                }, actions = if (identity.flag("has_override")) listOf(MenuAction("恢复继承权限", Icons.Filled.Restore) { vm.write(base + listOf("channel-managers", identity.text("channel_identity_id")), "DELETE") }) else emptyList()) }
            sectionError("channel-managers")
            channelAccessRules(state, vm, showEditor, confirm)
        }
        ManagementPage.Network -> {
            val settings = state.data["settings"].obj()
            val providers = state.data["network-meta"].objects()
            val selected = providers.firstOrNull { it.text("kind") == settings.text("overlay_provider") }.obj()
            item { GroupLabel("网络连接") }
            item { ManagementRow("启用专用网络", selected.text("display_name").ifBlank { "先选择网络类型并配置" }, Icons.Filled.Hub, 0, 2, editable,
                checked = settings.flag("overlay_enabled"), onChecked = { vm.write(base + "settings", "PUT", apiBody("overlay_enabled" to it)) }) }
            item { ManagementRow("网络类型", selected.text("display_name").ifBlank { "未设置" }, Icons.Filled.SettingsEthernet, 1, 2, editable, onClick = {
                edit("网络类型", listOf(FormField("overlay_provider", "类型", settings.text("overlay_provider"), choices = listOf("" to "不使用") + providers.map { it.text("kind") to it.text("display_name") })), base + "settings") { body ->
                    if (body.text("overlay_provider") == settings.text("overlay_provider")) body else JsonObject(body + mapOf("overlay_config" to JsonObject(emptyMap()), "overlay_enabled" to JsonPrimitive(false)))
                }
            }) }
            if (selected.isNotEmpty()) item { ManagementRow("连接配置", selected.text("description"), Icons.Filled.Tune, enabled = editable, onClick = {
                val config = settings["overlay_config"].obj()
                val schema = selected["config_schema"].obj()["fields"].objects()
                val fields = arraySchemaFields(schema, config, true)
                edit("${selected.text("display_name")} 配置", fields + FormField("advanced", "高级配置", formatted(JsonObject(config.filterKeys { key -> fields.none { it.key == key } })), FieldKind.Object, multiline = true), base + "settings") { body ->
                    apiBody("overlay_config" to JsonObject(config + body["advanced"].obj() + body.filterKeys { it != "advanced" }))
                }
            }) }
            val status = state.data["network-status"].obj()
            if (status.isNotEmpty()) item { ManagementRow(status.text("title").ifBlank { status.text("state") }, status.text("message").ifBlank { status.text("description") }, Icons.Filled.NetworkCheck, onClick = { vm.showResult(status) }) }
            sectionError("network-status")
            val actions = selected["actions"].objects()
            itemsIndexed(actions) { index, action -> ManagementRow(action.text("label"), action.text("description"), Icons.Filled.PlayArrow, index, actions.size,
                enabled = editable && settings.flag("overlay_enabled"), onClick = { vm.write(base + listOf("network", "actions", action.text("id")), "POST", apiBody("input" to JsonObject(emptyMap())), result = true) }) }
            val nodes = state.data["network-nodes"].objects("items").filter { it.flag("can_exit_node") }
            if (nodes.isNotEmpty()) item { ManagementRow("出口节点", nodes.firstOrNull { it.flag("selected") }?.text("display_name") ?: "默认出口", Icons.Filled.Route, enabled = editable, onClick = {
                edit("出口节点", listOf(FormField("exit_node", "节点", settings["overlay_config"].obj().text("exit_node"), choices = listOf("" to "默认出口") + nodes.map { it.text("value") to it.text("display_name") })), base + "settings") { apiBody("overlay_config" to JsonObject(settings["overlay_config"].obj() + it)) }
            }) }
            sectionError("network-nodes")
        }
        ManagementPage.Account -> {
            val user = state.data["user"].obj()
            item { GroupLabel("个人资料") }
            item { ManagementRow(user.text("display_name").ifBlank { user.text("username") }, user.text("timezone"), Icons.Filled.AccountCircle, enabled = editable, onClick = {
                val fields = listOf(FormField("display_name", "显示名称", user.text("display_name")), FormField("avatar_url", "头像地址", user.text("avatar_url")),
                    FormField("timezone", "时区", user.text("timezone"), omitBlank = true))
                if (state.cloud) showEditor(ManagementEditor("个人资料", fields) { body, saved -> vm.cloudWrite(listOf("users", "me"), "PATCH", validTimezone(body), onSaved = saved) })
                else edit("个人资料", fields, listOf("users", "me"), transform = ::validTimezone)
            }) }
            if (!state.cloud) item { ManagementRow("修改密码", "当前自托管账号", Icons.Filled.Password, enabled = editable, onClick = {
                edit("修改密码", listOf(FormField("current_password", "当前密码", secret = true, required = true), FormField("new_password", "新密码", secret = true, required = true), FormField("confirmation", "确认新密码", secret = true, required = true)), listOf("users", "me", "password")) { body ->
                    require(body.text("new_password") == body.text("confirmation")) { "两次输入的密码不一致" }; JsonObject(body.filterKeys { it != "confirmation" })
                }
            }) }
            item { GroupLabel("渠道身份") }
            if (state.data.containsKey("identities")) item { Actions { MemohActionButton("生成关联码", Icons.Filled.Link, {
                edit("关联渠道身份", listOf(FormField("channel_type", "渠道", choices = listOf("" to "任意渠道") + state.data["channels"].objects().map { it.text("type") to it.text("display_name") }, omitBlank = true)), listOf("users", "me", "channel-links"), "POST", result = true)
            }, enabled = editable) } }
            val identities = state.data["identities"].objects("items")
            itemsIndexed(identities) { index, identity -> ManagementRow(identity.text("channel_identity_display_name").ifBlank { identity.text("channel_subject_id") }, identity.text("channel_type"), Icons.Filled.Forum, index, identities.size,
                actions = listOf(MenuAction("解除关联", Icons.Filled.LinkOff) {
                    confirm(ConfirmAction("解除渠道身份关联", "此渠道身份将不再继承当前账号的 Bot 权限。") { vm.write(listOf("users", "me", "channel-identities", identity.text("channel_identity_id")), "DELETE") })
                }), enabled = editable) }
            if (identities.isEmpty() && state.data.containsKey("identities")) item { ManagementNotice("尚未关联渠道身份。") }
            sectionError("identities")
            val access = state.data["computer-access"].objects("items")
            if (access.isNotEmpty()) item { GroupLabel("电脑访问") }
            itemsIndexed(access) { index, computer -> ManagementRow(computer.text("name").ifBlank { computer.text("runtime_id") }, computer.text("bot_id"), Icons.Filled.Computer, index, access.size, onClick = { vm.showResult(computer) }) }
            sectionError("computer-access")
        }
        else -> Unit
    }
}

internal fun arraySchemaFields(fields: List<JsonObject>, config: JsonObject, configured: Boolean): List<FormField> = schemaFields(
    JsonObject(fields.sortedBy { (it["order"] as? JsonPrimitive)?.intOrNull ?: 0 }.associate { field -> field.text("key") to JsonObject(field +
        mapOf("type" to JsonPrimitive(when (field.text("type")) { "bool" -> "boolean"; "secret" -> "password"; else -> field.text("type") }))) }), config, configured)

internal fun sizeText(value: String): String = value.toLongOrNull()?.let { "%.1f MB".format(it / 1048576.0) } ?: "暂无数据"
