package dev.memoh.feature.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RuntimeCommandOutput(text: String, onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Terminal, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("命令结果", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "关闭命令结果") }
            }
            SelectionContainer {
                Text(text, Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState()).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
