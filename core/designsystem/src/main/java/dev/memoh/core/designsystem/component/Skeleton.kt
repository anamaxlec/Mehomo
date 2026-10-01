package dev.memoh.core.designsystem.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/** Refreshes with existing data keep their content; only unavailable content is covered. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MemohLoadingContent(
    loading: Boolean,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Crossfade(loading, modifier.fillMaxSize(), animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "content loaded") { waiting ->
        if (waiting) Surface(Modifier.fillMaxSize(), color = backgroundColor) { placeholder() }
        else content()
    }
}

/** Shared placeholder motion; duration follows the system animation scale. */
@Composable
fun MemohSkeleton(
    modifier: Modifier = Modifier,
    description: String = "正在加载内容",
    content: @Composable ColumnScope.() -> Unit,
) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = .55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeleton opacity",
    )
    Column(
        modifier = modifier.clipToBounds().graphicsLayer { alpha = pulse }
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun MemohSkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Box(modifier.background(LocalContentColor.current.copy(alpha = .12f), shape))
}

/** Uses the same native grouped rows as the loaded list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MemohListSkeleton(
    modifier: Modifier = Modifier,
    description: String = "正在加载列表",
    leading: Boolean = false,
    detailed: Boolean = false,
    rows: Int = 5,
) {
    MemohSkeleton(modifier.fillMaxWidth(), description) {
        MemohSkeletonBlock(Modifier.padding(start = 16.dp, top = 12.dp).width(64.dp).height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            repeat(rows) { index ->
                SegmentedListItem(
                    onClick = {}, enabled = false,
                    shapes = ListItemDefaults.segmentedShapes(index, rows),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    leadingContent = if (leading) {{
                        MemohSkeletonBlock(Modifier.size(28.dp), MaterialTheme.shapes.medium)
                    }} else null,
                    supportingContent = if (detailed) {{
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MemohSkeletonBlock(Modifier.fillMaxWidth(.82f).height(12.dp))
                            MemohSkeletonBlock(Modifier.fillMaxWidth(.6f).height(12.dp))
                        }
                    }} else null,
                    content = {
                        MemohSkeletonBlock(Modifier.fillMaxWidth(if (index % 2 == 0) .7f else .5f).height(18.dp))
                    },
                )
            }
        }
    }
}

@Composable
fun MemohTextSkeleton(modifier: Modifier = Modifier, description: String = "正在加载内容") {
    MemohSkeleton(modifier.fillMaxWidth(), description) {
        MemohSkeletonBlock(Modifier.fillMaxWidth(.36f).height(20.dp))
        repeat(3) {
            Spacer(Modifier.height(8.dp))
            listOf(1f, .94f, .98f, .65f).forEach { fraction ->
                MemohSkeletonBlock(Modifier.fillMaxWidth(fraction).height(12.dp))
            }
        }
    }
}
