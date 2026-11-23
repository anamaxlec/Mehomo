package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ListIconTone { Primary, Secondary, Tertiary, Error }

/** Tonal leading icons for lists and feedback. */
@Composable
fun MemohListIcon(icon: ImageVector, tone: ListIconTone = ListIconTone.Primary, size: Dp = 40.dp) {
    val colors = MaterialTheme.colorScheme
    val (container, content, outline) = when (tone) {
        ListIconTone.Primary -> Triple(colors.primaryContainer, colors.onPrimaryContainer, MaterialShapes.Square)
        ListIconTone.Secondary -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, MaterialShapes.Circle)
        ListIconTone.Tertiary -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, MaterialShapes.Cookie4Sided)
        ListIconTone.Error -> Triple(colors.errorContainer, colors.onErrorContainer, MaterialShapes.Cookie6Sided)
    }
    Surface(shape = outline.toShape(), color = container, contentColor = content, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * 0.55f)) }
    }
}
