package dev.memoh.feature.chat.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import dev.memoh.core.markdown.LocalMarkdownImageLoader
import dev.memoh.core.markdown.MarkdownImage
import dev.memoh.core.markdown.DocumentPreview
import dev.memoh.core.model.UIAttachment
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
    val loader = LocalMarkdownImageLoader.current
    val source = attachmentSource(attachment, LocalChatBotId.current)
    val name = attachment.name ?: attachment.path?.substringAfterLast('/') ?: "附件"
    var preview by remember(source) { mutableStateOf(false) }
    if (source != null && attachment.mime != "image/svg+xml" && !name.endsWith(".svg", true) && (attachment.type == "image" || attachment.mime?.startsWith("image/") == true)) {
        MarkdownImage(source, name)
    } else AssistChip(
        onClick = { preview = true },
        enabled = source != null && loader != null,
        label = { Text(name) },
        leadingIcon = { Icon(Icons.Filled.AttachFile, null) },
    )
    if (preview && source != null) DocumentPreview(source, name, attachment.mime) { preview = false }
}
