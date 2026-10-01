package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Compact native expressive action shared by feature cards and dialogs. */
@Composable
fun MemohActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
    val padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    if (primary) Button(onClick = onClick, modifier = modifier, enabled = enabled,
        shapes = ButtonDefaults.shapes(), contentPadding = padding,
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
        content = content)
    else FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled,
        shapes = ButtonDefaults.shapes(), contentPadding = padding,
        colors = if (danger) ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        content = content)
}
