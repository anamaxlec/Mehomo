package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The Material 3 Expressive primitives this app depends on, exercised in one
 * place. These APIs exist only in the material3 1.5.0 alpha line — this file is
 * the compile-time proof that the pinned version carries them, so a version bump
 * that drops one fails here instead of deep inside a feature.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MemohExpressivePrimitives(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Indeterminate wait state — replaces CircularProgressIndicator app-wide.
        LoadingIndicator(modifier = Modifier.size(32.dp))

        // Decision panels: approve/reject and agent permission options.
        ButtonGroup(
            overflowIndicator = {},
            modifier = Modifier.fillMaxWidth(),
        ) {
            clickableItem(
                onClick = {},
                label = "批准",
                icon = { Icon(Icons.Filled.Check, contentDescription = null) },
                weight = 1f,
            )
            clickableItem(
                onClick = {},
                label = "拒绝",
                icon = { Icon(Icons.Filled.Close, contentDescription = null) },
                weight = 1f,
            )
        }

        // Message actions, revealed on long-press rather than parked in the row.
        HorizontalFloatingToolbar(expanded = true) {
            ToolbarAction(Icons.Filled.ContentCopy, "复制")
            ToolbarAction(Icons.Filled.Share, "分享")
        }

        // The composer's "+" attachment menu.
        FloatingActionButtonMenu(
            expanded = false,
            button = {
                ToggleFloatingActionButton(checked = false, onCheckedChange = {}) {
                    Icon(Icons.Filled.Add, contentDescription = "附件")
                }
            },
        ) { }

        // Model · effort picker: primary action plus its expand affordance.
        SplitButtonLayout(
            leadingButton = {
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("模型 · 强度") }
            },
            trailingButton = {
                IconButton(onClick = {}) { Icon(Icons.Filled.Add, contentDescription = "展开") }
            },
        )
    }
}

@Composable
private fun RowScope.ToolbarAction(icon: ImageVector, label: String) {
    IconButton(onClick = {}) {
        Icon(icon, contentDescription = label)
    }
}
