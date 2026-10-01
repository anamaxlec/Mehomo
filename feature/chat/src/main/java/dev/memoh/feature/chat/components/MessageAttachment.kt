package dev.memoh.feature.chat.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.memoh.core.markdown.LocalMarkdownImageLoader
import dev.memoh.core.markdown.MarkdownImage
import dev.memoh.core.model.UIAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

val LocalChatBotId = staticCompositionLocalOf { "" }

internal fun attachmentSource(attachment: UIAttachment, currentBotId: String): String? {
    fun escape(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    val bot = attachment.botId?.takeIf(String::isNotBlank) ?: currentBotId
    val hash = attachment.contentHash?.takeIf(String::isNotBlank)
    if (hash != null && bot.isNotBlank()) return "/bots/${escape(bot)}/media/${escape(hash)}"
    attachment.base64?.takeIf(String::isNotBlank)?.let {
        return if (it.startsWith("data:")) it else "data:${attachment.mime ?: "application/octet-stream"};base64,$it"
    }
    attachment.url?.takeIf(String::isNotBlank)?.let { return it }
    attachment.path?.takeIf { it.isNotBlank() && bot.isNotBlank() }?.let {
        return "/bots/${escape(bot)}/container/fs/download?path=${escape(it)}"
    }
    return null
}

@Composable
fun MessageAttachment(attachment: UIAttachment) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = LocalMarkdownImageLoader.current
    val source = attachmentSource(attachment, LocalChatBotId.current)
    val name = attachment.name ?: attachment.path?.substringAfterLast('/') ?: "附件"
    var saving by remember { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(attachment.mime ?: "application/octet-stream")) { uri ->
        if (uri != null && source != null) scope.launch {
            saving = true
            try {
                val bytes = requireNotNull(loader) { "文件加载不可用" }.invoke(source)
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)) { "无法写入所选位置" }.use { it.write(bytes) }
                }
                Toast.makeText(context, "文件已保存", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, e.message ?: "保存失败", Toast.LENGTH_LONG).show() }
            finally { saving = false }
        }
    }
    if (source != null && (attachment.type == "image" || attachment.mime?.startsWith("image/") == true)) {
        MarkdownImage(source, name)
    } else AssistChip(
        onClick = { saver.launch(name) },
        enabled = source != null && loader != null && !saving,
        label = { Text(if (saving) "保存中…" else name) },
        leadingIcon = { Icon(Icons.Filled.AttachFile, null) },
    )
}
