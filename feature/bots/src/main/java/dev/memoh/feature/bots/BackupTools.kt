package dev.memoh.feature.bots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.*
import kotlinx.serialization.json.*

data class BackupImport(val name: String, val bytes: ByteArray, val mode: String, val passphrase: String, val preview: JsonObject)
private val sectionNames = mapOf("profile" to "Bot 资料", "settings" to "设置", "models" to "模型", "acl" to "成员权限", "channels" to "消息渠道",
    "mcp" to "MCP", "schedules" to "定时任务", "history" to "会话历史", "assets" to "附件", "workspace" to "工作空间文件")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupTools(state: ManagementState, vm: ManagementViewModel, onOpenBot: (String) -> Unit) {
    val context = LocalContext.current
    var editor by remember { mutableStateOf<ManagementEditor?>(null) }
    var exportSections by remember { mutableStateOf<List<String>>(emptyList()) }
    var exportPassphrase by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val selected = remember(state.backupImport) { mutableStateMapOf<String, String>().apply {
        state.backupImport?.preview?.get("sections").objects().forEach { if (it.text("count").toLongOrNull()?.let { count -> count > 0 } == true) put(it.text("key"), "merge") }
    } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(context, uri, exportSections, exportPassphrase)
        exportPassphrase = ""
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.inspectBackup(context, uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupLabel("导出当前 Bot")
        val summary = state.data["summary"].obj()
        val available = summary["sections"].objects().filter { (it["count"] as? JsonPrimitive)?.longOrNull?.let { count -> count > 0 } == true }
        if (state.bot == null) ManagementNotice("选择 Bot 后可导出其备份。")
        state.data["summary-error"]?.let { ManagementNotice((it as? JsonPrimitive)?.content.orEmpty(), true) }
        available.forEachIndexed { index, section -> ManagementRow(sectionNames[section.text("key")] ?: section.text("key"),
            "${section.text("count")} 项${if (section.flag("sensitive")) " · 含认证或权限配置" else ""}", Icons.Filled.FolderZip, index, available.size) }
        if (available.isEmpty() && state.data.containsKey("summary")) ManagementNotice("当前 Bot 尚无可导出的内容。")
        Actions { MemohActionButton("导出备份", Icons.Filled.FileDownload, {
            vm.dismiss()
            editor = ManagementEditor("导出备份", listOf(FormField("sections", "导出内容", JsonArray(available.map { JsonPrimitive(it.text("key")) }).toString(), FieldKind.StringSet,
                choices = available.map { it.text("key") to (sectionNames[it.text("key")] ?: it.text("key")) }),
                FormField("passphrase", "加密密码（可选）", secret = true)), confirmLabel = "导出") { body, close ->
                exportSections = (body["sections"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                require(exportSections.isNotEmpty()) { "请选择导出内容" }
                exportPassphrase = body.text("passphrase")
                close(); exporter.launch("${state.bot?.name ?: "bot"}-${java.time.LocalDate.now()}.memoh.zip")
            }
        }, enabled = !state.busy && state.canManageBot && available.isNotEmpty(), primary = true) }
        GroupLabel("导入备份")
        Actions { MemohActionButton("选择备份文件", Icons.Filled.FileUpload, { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !state.busy, primary = true) }
        val draft = state.backupImport
        if (draft == null) Text("先预览备份，再选择新建 Bot 或恢复到当前 Bot。", style = MaterialTheme.typography.bodyMedium)
        else {
            ManagementRow(draft.name, if (draft.mode == "create") "新建 Bot" else "恢复到 ${state.bot?.displayName ?: state.bot?.name}", Icons.Filled.FolderZip,
                actions = listOf(MenuAction("移除文件", Icons.Filled.Close, vm::clearBackup)), enabled = !state.busy)
            Actions {
                MemohActionButton("导入设置", Icons.Filled.Tune, {
                    editor = ManagementEditor("导入设置", listOf(FormField("mode", "恢复目标", draft.mode, choices = listOf("create" to "新建 Bot") + if (state.canManageBot) listOf("overwrite" to "当前 Bot") else emptyList()),
                        FormField("passphrase", "加密密码", secret = true)), confirmLabel = "预览") { body, close ->
                        close(); vm.inspectBackup(mode = body.text("mode"), passphrase = body.text("passphrase"))
                    }
                }, enabled = !state.busy)
            }
            if (draft.preview.flag("requires_passphrase")) ManagementNotice("此备份已加密，请在导入设置中输入密码并重新预览。")
            else {
                val sections = draft.preview["sections"].objects()
                sections.forEachIndexed { index, section ->
                    val key = section.text("key")
                    val count = section.text("count").toLongOrNull() ?: 0
                    ManagementRow(sectionNames[key] ?: key, "$count 项${if (section.flag("conflict")) " · 当前 Bot 已有内容" else ""}", Icons.Filled.Folder, index, sections.size,
                        enabled = !state.busy && count > 0, onClick = {
                            editor = ManagementEditor("${sectionNames[key] ?: key}恢复方式", listOf(FormField("strategy", "方式", selected[key] ?: "skip",
                                choices = listOf("skip" to "跳过", "merge" to "合并", "replace" to "替换已有内容")))) { body, close -> selected[key] = body.text("strategy"); close() }
                        }, actions = emptyList(), checked = selected[key] != null && selected[key] != "skip",
                        onChecked = { selected[key] = if (it) "merge" else "skip" })
                }
                if (draft.mode == "overwrite") ManagementRow("恢复 Bot 资料", "名称、头像和基础资料", Icons.Filled.SmartToy,
                    checked = selected["profile"] == "merge", onChecked = { selected["profile"] = if (it) "merge" else "skip" }, enabled = !state.busy)
                draft.preview["warnings"]?.let { value -> (value as? JsonArray).orEmpty().forEach { ManagementNotice(it.jsonPrimitive.content) } }
                Actions { MemohActionButton(if (draft.mode == "create") "创建并恢复 Bot" else "恢复到当前 Bot", Icons.Filled.Restore, { confirming = true },
                    enabled = !state.busy && selected.values.any { it != "skip" }, primary = true) }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    editor?.let { current -> ManagementForm(current, state.busy, state.error) { editor = null } }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("恢复备份") }, text = {
        Text(if (state.backupImport?.mode == "overwrite") "将按选定方式恢复到当前 Bot。标为替换的内容会覆盖现有数据。" else "将创建一个 Bot 并恢复选定的内容。")
    }, confirmButton = { MemohActionButton("恢复", Icons.Filled.Restore, {
        confirming = false; vm.importBackup(JsonObject(selected.mapValues { JsonPrimitive(it.value) }), onOpenBot)
    }, primary = true) }, dismissButton = { TextButton({ confirming = false }) { Text("取消") } })
}
