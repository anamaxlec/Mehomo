package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class ListIconTone { Primary, Secondary, Tertiary, Error }

/** Decorative leading icon for Material 3 lists; colors follow the active theme. */
@Composable
fun MemohListIcon(icon: ImageVector, tone: ListIconTone = ListIconTone.Primary) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ListIconTone.Primary -> colors.primaryContainer to colors.onPrimaryContainer
        ListIconTone.Secondary -> colors.secondaryContainer to colors.onSecondaryContainer
        ListIconTone.Tertiary -> colors.tertiaryContainer to colors.onTertiaryContainer
        ListIconTone.Error -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
    }
}
