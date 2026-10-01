package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Native large-page title that folds into the compact navigation bar on scroll. */
@Composable
fun MemohPageTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    large: Boolean = true,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.primary,
        actionIconContentColor = MaterialTheme.colorScheme.primary,
    )
    val titleContent: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val navigation: @Composable () -> Unit = {
        onBack?.let { back ->
            FilledTonalIconButton(onClick = back, enabled = backEnabled,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(22.dp))
            }
        }
    }
    if (large) {
        var actionsWidth by remember { mutableIntStateOf(0) }
        val fraction = scrollBehavior?.state?.collapsedFraction ?: 0f
        // Keep the native app bar's scroll/drag/snap behavior, but render one
        // title. The default large bar crossfades two differently sized titles.
        Box {
            LargeTopAppBar(title = {}, navigationIcon = navigation,
                actions = { Row(Modifier.onSizeChanged { actionsWidth = it.width }, content = actions) },
                colors = colors, scrollBehavior = scrollBehavior, windowInsets = windowInsets)
            Layout(modifier = Modifier.matchParentSize().windowInsetsPadding(windowInsets),
                content = {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        style = lerp(MaterialTheme.typography.headlineLarge, MaterialTheme.typography.titleLarge, fraction))
                }) { measurables, constraints ->
                val start = (16.dp.toPx() + (if (onBack != null) 56.dp.toPx() else 0f) * fraction).roundToInt()
                val end = (16.dp.toPx() + actionsWidth * fraction).roundToInt()
                val text = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0,
                    maxWidth = (constraints.maxWidth - start - end).coerceAtLeast(0)))
                val expandedY = TopAppBarDefaults.LargeAppBarExpandedHeight.toPx() - text.height - 24.dp.toPx()
                val collapsedY = (TopAppBarDefaults.LargeAppBarCollapsedHeight.toPx() - text.height) / 2f
                layout(constraints.maxWidth, constraints.maxHeight) {
                    text.placeRelative(start, (expandedY + (collapsedY - expandedY) * fraction).roundToInt())
                }
            }
        }
    } else TopAppBar(title = titleContent, navigationIcon = navigation,
        actions = actions, colors = colors, windowInsets = windowInsets)
}
