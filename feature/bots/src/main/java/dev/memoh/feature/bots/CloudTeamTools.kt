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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun teamRole(role: String) = role.substringAfterLast('_').lowercase()
private fun roleLabel(role: String) = when (teamRole(role)) { "owner" -> "所有者"; "admin" -> "管理员"; "member" -> "成员"; else -> role }
private fun credits(value: String) = value.toBigDecimalOrNull()?.divide(BigDecimal(1_000_000), 2, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: "—"
internal fun managementTimestamp(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

/** Cloud profile data is separate from the Bot service account, whose names can be empty. */
internal fun cloudUserProfile(value: JsonElement): JsonObject {
    val root = value.obj()
    val user = root["user"].obj()
    val profile = root["user_profile"].obj()
    fun first(vararg values: String) = values.firstOrNull(String::isNotBlank).orEmpty()
    return apiBody("id" to first(root.text("id"), root.text("user_id"), user.text("id"), user.text("user_id")),
        "username" to first(root.text("username"), user.text("username")),
        "display_name" to first(root.text("display_name"), profile.text("display_name"), user.text("display_name")),
        "avatar_url" to first(root.text("avatar_url"), profile.text("avatar_url"), user.text("avatar_url")),
        "timezone" to first(root.text("timezone"), profile.text("timezone"), user.text("timezone"), "UTC"))
}

internal fun LazyListScope.cloudTeamTools(state: ManagementState, vm: ManagementViewModel, editor: (ManagementEditor) -> Unit,
    confirm: (ConfirmAction) -> Unit, openUrl: (String) -> Unit) {
    val membership = state.data["membership"].obj()
    val team = membership["team"].obj()
    val teamId = team.text("team_id")
    val base = listOf("teams", teamId)
    val owner = teamRole(membership.text("role")) == "owner"
    val editable = state.canManage && !state.busy
    fun edit(title: String, fields: List<FormField>, path: List<String>, method: String) {
        vm.dismiss()
        editor(ManagementEditor(title, fields, if (path.lastOrNull() == "invitations") "发送邀请" else "保存") { body, saved -> vm.cloudWrite(path, method, body, onSaved = saved) })
    }
    item { Actions { MemohActionButton("创建团队", Icons.Filled.Add, {
        edit("创建 Cloud 团队", listOf(FormField("name", "团队名称", required = true), FormField("avatar_url", "头像地址", omitBlank = true), FormField("description", "说明", omitBlank = true)), listOf("teams"), "POST")
    }, enabled = !state.busy) } }
    if (teamId.isBlank()) { item { ManagementNotice("尚未选择团队，请在“我的”中选择团队，或先创建一个。") }; return }
    item { GroupLabel("团队资料") }
    item { ManagementRow(team.text("name"), roleLabel(membership.text("role")), Icons.Filled.Groups, enabled = editable, onClick = {
        edit("团队资料", listOf(FormField("name", "团队名称", team.text("name"), required = true), FormField("avatar_url", "头像地址", team.text("avatar_url")), FormField("description", "说明", team.text("description"))), base, "PATCH")
    }) }
    val members = state.data["members"].objects("members")
    val claims = state.data["entitlements"].obj()["effective_claims"].obj()["claims"].obj()
    val seats = claims.text("team.max_seats").toIntOrNull()
    item { GroupLabel("团队成员${seats?.let { "（${members.size}/$it）" }.orEmpty()}") }
    item { Actions { MemohActionButton("邀请成员", Icons.Filled.PersonAdd, {
        edit("邀请团队成员", listOf(FormField("email", "邮箱", required = true), FormField("preferred_locale", "邀请语言", "zh", choices = listOf("zh" to "中文", "en" to "English"))), base + "invitations", "POST")
    }, enabled = editable && (seats == null || members.size < seats), primary = true) } }
    if (seats != null && members.size >= seats) item { ManagementNotice("已达到当前套餐的成员上限。") }
    itemsIndexed(members, key = { _, member -> "cloud-member:${member.text("user_id")}" }) { index, member ->
        val name = member.text("display_name").ifBlank { member.text("username").ifBlank { member.text("email") } }
        val memberRole = teamRole(member.text("role"))
        val actions = if (editable && memberRole != "owner" && member.text("user_id") != (state.data["self-user-id"] as? JsonPrimitive)?.content) buildList {
            if (owner) add(MenuAction("修改角色", Icons.Filled.ManageAccounts) {
                edit("修改 $name 的角色", listOf(FormField("role", "角色", member.text("role"), choices = listOf("TEAM_ROLE_ADMIN" to "管理员", "TEAM_ROLE_MEMBER" to "成员"))), base + listOf("members", member.text("user_id")), "PATCH")
            })
            add(MenuAction("移除成员", Icons.Filled.PersonRemove) {
                confirm(ConfirmAction("移除团队成员", "$name 将失去此团队的访问权限。") { vm.cloudWrite(base + listOf("members", member.text("user_id")), "DELETE") })
            })
            if (owner) add(MenuAction("转移所有权", Icons.Filled.AdminPanelSettings) {
                confirm(ConfirmAction("转移团队所有权", "$name 将成为团队所有者，你将不再拥有所有者权限。") { vm.cloudWrite(base + "owner", "PUT", apiBody("new_owner_user_id" to member.text("user_id"))) })
            })
        } else emptyList()
        ManagementRow(name, listOf(roleLabel(member.text("role")), member.text("email")).filter(String::isNotBlank).joinToString(" · "),
            Icons.Filled.Person, index, members.size, actions = actions)
    }
    state.data["members-error"]?.let { item { ManagementNotice((it as? JsonPrimitive)?.content.orEmpty(), true) } }
    val invitations = state.data["invitations"].objects("invitations")
    if (invitations.isNotEmpty()) item { GroupLabel("待接受邀请") }
    itemsIndexed(invitations, key = { _, invitation -> "cloud-invite:${invitation.text("invitation_id")}" }) { index, invitation ->
        ManagementRow(invitation.text("email"), "有效期至 ${managementTimestamp(invitation.text("expires_at"))}", Icons.Filled.MailOutline, index, invitations.size,
            actions = if (editable) listOf(MenuAction("撤销邀请", Icons.Filled.Close) {
                confirm(ConfirmAction("撤销邀请", "撤销发送给 ${invitation.text("email")} 的邀请。") { vm.cloudWrite(base + listOf("invitations", invitation.text("invitation_id")), "DELETE") })
            }) else emptyList())
    }
    item { GroupLabel("Cloud 套餐额度") }
    val billing = state.data["credits"].obj()
    val included = billing["included"].obj()
    if (included.isNotEmpty()) item { Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("剩余 ${credits(included.text("remaining_micros"))} Credits", style = MaterialTheme.typography.titleLarge)
            Text("已用 ${credits(included.text("used_micros"))} / ${credits(included.text("limit_micros"))} Credits")
            val limit = included.text("limit_micros").toDoubleOrNull() ?: 0.0
            val used = included.text("used_micros").toDoubleOrNull() ?: 0.0
            LinearProgressIndicator(progress = { if (limit > 0.0) (used / limit).toFloat().coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
            billing.text("period_end").takeIf(String::isNotBlank)?.let { Text("周期截止 ${managementTimestamp(it)}", style = MaterialTheme.typography.bodySmall) }
        }
    } }
    state.data["credits-error"]?.let { item { ManagementNotice((it as? JsonPrimitive)?.content.orEmpty()) } }
    val resources = listOf("CPU 上限" to claims.text("max_cpu_milli").toLongOrNull()?.let { "${it / 1000.0} 核" }, "内存上限" to claims.text("max_memory_bytes").takeIf(String::isNotBlank)?.let(::sizeText), "存储上限" to claims.text("max_storage_bytes").takeIf(String::isNotBlank)?.let(::sizeText)).filter { it.second != null }
    itemsIndexed(resources) { index, resource -> ManagementRow(resource.first, resource.second.orEmpty(), Icons.Filled.Speed, index, resources.size) }
    item { Actions { MemohActionButton("账单与订阅", Icons.Filled.OpenInNew, { openUrl("https://app.memoh.net/settings/billing") }) } }
    if (owner) item { ManagementRow("删除团队", "删除团队及其 Bot、文件和历史数据", Icons.Filled.DeleteOutline, enabled = editable, onClick = {
        confirm(ConfirmAction("删除 Cloud 团队", "${team.text("name")} 的数据将由后台清理，此操作无法撤销。") { vm.cloudWrite(base, "DELETE", removedTeam = true) })
    }) }
}
