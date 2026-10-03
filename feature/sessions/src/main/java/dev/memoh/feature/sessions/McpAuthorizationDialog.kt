package dev.memoh.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohActionButton
import kotlinx.serialization.json.*

@Composable
internal fun McpAuthorizationDialog(auth: McpAuthorization, busy: Boolean, error: String?, vm: BotFeatureViewModel) {
    var clientId by remember(auth.id) { mutableStateOf("") }
    var clientSecret by remember(auth.id) { mutableStateOf("") }
    var callback by remember(auth.id) { mutableStateOf("") }
    var manual by remember(auth.id) { mutableStateOf(false) }
    var revoke by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val authorized = (auth.status["has_token"] as? JsonPrimitive)?.booleanOrNull == true && (auth.status["expired"] as? JsonPrimitive)?.booleanOrNull != true
    LaunchedEffect(auth.url) { if (auth.url != null) clientSecret = "" }
    AlertDialog(onDismissRequest = { if (!busy) vm.closeMcpAuthorization() }, title = { Text("${auth.name} 授权") }, icon = { Icon(Icons.Filled.Link, null) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (authorized) "已授权" else if ((auth.status["expired"] as? JsonPrimitive)?.booleanOrNull == true) "授权已过期" else "等待授权")
            if (authorized) {
                (auth.status["scopes"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { Text("权限：$it") }
                MemohActionButton("断开授权", Icons.Filled.LinkOff, { revoke = true }, enabled = !busy, danger = true)
            } else if (auth.url == null) {
                Text(if (auth.needsClientId) "此服务不提供自动注册，请填写服务提供的 OAuth client ID。" else "获取授权链接后，在浏览器登录并确认权限。")
                OutlinedTextField(clientId, { clientId = it }, label = { Text("OAuth client ID（可选）") }, singleLine = true, enabled = !busy)
                OutlinedTextField(clientSecret, { clientSecret = it }, label = { Text("Client secret（可选）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            } else {
                Text("在浏览器完成授权，返回后自动更新连接状态。")
                MemohActionButton("在浏览器授权", Icons.Filled.OpenInNew, {
                    try { openError = null; uriHandler.openUri(auth.url) } catch (e: Exception) { openError = "无法打开浏览器" }
                }, enabled = !busy, primary = true)
                TextButton({ manual = !manual }, enabled = !busy) { Text("手动提交回调链接") }
                if (manual) {
                    OutlinedTextField(callback, { callback = it }, label = { Text("浏览器中的完整回调地址") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                    MemohActionButton("提交回调", Icons.Filled.Check, { vm.exchangeMcpCallback(callback); callback = "" }, enabled = !busy && callback.isNotBlank())
                }
            }
            if (busy) LoadingIndicator(Modifier.size(32.dp))
            (error ?: openError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = {
            when {
                authorized -> MemohActionButton("完成", Icons.Filled.Check, vm::closeMcpAuthorization, enabled = !busy, primary = true)
                auth.url == null -> MemohActionButton("获取授权链接", Icons.Filled.Login, { vm.authorizeMcp(clientId.trim(), clientSecret) }, enabled = !busy, primary = true)
                else -> MemohActionButton("检查授权状态", Icons.Filled.Refresh, vm::checkMcpAuthorization, enabled = !busy)
            }
        }, dismissButton = { if (!authorized) TextButton(vm::closeMcpAuthorization, enabled = !busy) { Text("关闭") } })
    if (revoke) AlertDialog(onDismissRequest = { revoke = false }, title = { Text("断开 MCP 授权") }, text = { Text("此连接需要重新授权后才能使用受保护的工具。") },
        confirmButton = { MemohActionButton("断开", Icons.Filled.LinkOff, { revoke = false; vm.revokeMcpAuthorization() }, enabled = !busy, danger = true, primary = true) },
        dismissButton = { TextButton({ revoke = false }) { Text("取消") } })
}
