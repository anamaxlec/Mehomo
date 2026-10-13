package dev.memoh.feature.bots

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.model.MemohError
import kotlinx.serialization.json.*

internal fun LazyListScope.dependencyRows(state: ManagementState, vm: ManagementViewModel,
    confirm: (ConfirmAction) -> Unit, onOpenApps: () -> Unit) {
    val data = state.data["dependencies"].obj()
    val entries = data["items"].objects()
    val editable = state.canManageBot && !state.busy
    item { Actions { MemohActionButton("检查更新", Icons.Filled.Update, vm::checkDependencyUpdates, enabled = editable) } }
    if (data.flag("update_checks_incomplete")) item { ManagementNotice("有些依赖尚未完成更新检查，列表保留已有结果。") }
    if (data.text("workspace_state") != "running") item { ManagementNotice("请先启动工作空间，再安装或更新依赖。") }
    if (data.text("discovery_error").isNotBlank()) item { ManagementNotice(MemohError(data.text("discovery_error")).text(), true) }
    itemsIndexed(entries, key = { _, entry -> entry.text("id") }) { index, entry ->
        val actions = (entry["actions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val description = listOf(entry.text("installed_version").ifBlank { "尚未安装" },
            if (entry.flag("update_available")) "可更新至 ${entry.text("latest_version")}" else "",
            if (!entry.flag("platform_supported", true)) "当前平台不支持" else "").filter(String::isNotBlank).joinToString(" · ")
        ManagementRow(entry.text("name").ifBlank { entry.text("id") }, description, Icons.Filled.Build, index, entries.size,
            onClick = { vm.showResult(entry) },
            actions = listOf("install" to "安装", "update" to "更新", "reinstall" to "重新安装", "rollback" to "回退").filter { it.first in actions }.map { (action, label) ->
                MenuAction(label, Icons.Filled.Build) {
                    if (editable) confirm(ConfirmAction("$label ${entry.text("name")}", "将在当前 Bot 工作空间执行此操作，所需前置依赖也可能被安装。") { vm.dependencyAction(entry.text("id"), action) })
                }
            } + if ("remove" in actions) listOf(MenuAction("前往应用移除", Icons.Filled.DeleteOutline, onOpenApps)) else emptyList())
        entry.text("last_error_code").takeIf(String::isNotBlank)?.let { ManagementNotice(MemohError(it).text(), true) }
        if (entry.text("last_error_code").isBlank() && entry.text("last_error").isNotBlank()) ManagementNotice("上次依赖操作失败，请重试或重新安装。", true)
    }
    if (entries.isEmpty()) item { ManagementNotice("当前工作空间没有可显示的依赖。") }
}

fun managementPageForError(action: dev.memoh.core.model.ErrorAction): ManagementPage = when (action) {
    dev.memoh.core.model.ErrorAction.BotSettings -> ManagementPage.Bot
    dev.memoh.core.model.ErrorAction.Dependencies -> ManagementPage.Dependencies
    dev.memoh.core.model.ErrorAction.Agents -> ManagementPage.Agents
    dev.memoh.core.model.ErrorAction.Connectors -> ManagementPage.Connectors
}
