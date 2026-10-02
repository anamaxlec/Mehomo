package dev.memoh.feature.chat.components

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.designsystem.component.MemohComposerIconButton
import dev.memoh.core.designsystem.component.MemohListIcon
import dev.memoh.core.designsystem.component.ListIconTone
import dev.memoh.core.markdown.MarkdownImage
import dev.memoh.core.model.ChatAttachment
import dev.memoh.feature.chat.attachmentSize
import java.util.Locale

/** A bounded file tray within the expanded composer, including per-file removal. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComposerAttachments(
    attachments: List<ChatAttachment>,
    loading: Boolean,
    onRemove: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(24.dp))
            Text("正在读取附件…", Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall)
            MemohActionButton("取消", Icons.Filled.Close, onCancel)
        }
        if (attachments.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            attachments.forEachIndexed { index, attachment ->
                val name = attachment.name ?: "附件"
                val type = attachment.name?.substringAfterLast('.', "")
                    ?.takeIf(String::isNotBlank)?.take(12)?.uppercase(Locale.ROOT)
                    ?: if (attachment.type == "image") "图片" else "文件"
                Surface(Modifier.width(168.dp), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().height(96.dp)) {
                            if (attachment.type == "image") MarkdownImage(
                                attachment.base64, name, height = 96.dp, showCaption = false,
                            ) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                MemohListIcon(Icons.Filled.Description, ListIconTone.Secondary)
                            }
                            Box(Modifier.align(Alignment.TopEnd)) {
                                MemohComposerIconButton(Icons.Filled.Close, "移除附件 $name",
                                    enabled = true, onClick = { onRemove(index) })
                            }
                        }
                        Text(name, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$type · ${Formatter.formatShortFileSize(context, attachmentSize(attachment))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
