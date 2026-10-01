package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/** Composer menus share the input field's horizontal boundaries. */
val LocalMemohPopupBounds = staticCompositionLocalOf<IntRect?> { null }

/** Menus used while composing must leave the editor's window and IME focused. */
val LocalMemohPopupFocusable = staticCompositionLocalOf { true }

/** A menu overlapping its control and constrained to its owner's horizontal bounds. */
@Composable
fun MemohPopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    /** Which corner of the button the menu grows from. */
    alignment: Alignment,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    maxHeight: Dp = 320.dp,
    scrollable: Boolean = true,
    focusable: Boolean = LocalMemohPopupFocusable.current,
    content: @Composable () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bounds = LocalMemohPopupBounds.current
    val maxWidth = with(density) { bounds?.width?.toDp() }?.coerceAtMost(248.dp) ?: 248.dp
    val shadowSpace = 12.dp
    var geometry by remember { mutableStateOf<MemohMenuGeometry?>(null) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded && geometry != null
    val positionProvider = remember(alignment, bounds, density) {
        MemohMenuPositionProvider(
            alignment = alignment, ownerBounds = bounds,
            margin = with(density) { 16.dp.roundToPx() },
            gap = with(density) { 12.dp.roundToPx() },
            shadowPadding = with(density) { shadowSpace.roundToPx() },
            onPosition = { geometry = it },
        )
    }
    @Suppress("DEPRECATION")
    val transition = updateTransition(visibility, "menuFromTrigger")
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val progress by transition.animateFloat(transitionSpec = { spatialSpec }, label = "menuBounds") { if (it) 1f else 0f }
    val opacity by transition.animateFloat(transitionSpec = { effectsSpec }, label = "menuOpacity") { if (it) 1f else 0f }
    LaunchedEffect(expanded, visibility.isIdle) {
        if (!expanded && visibility.isIdle) geometry = null
    }
    if (expanded || visibility.currentState || visibility.targetState) {
        val menuShape = MenuDefaults.groupShape(0, 1).shape
        val measured = geometry
        val reveal = progress.coerceIn(0f, 1f)
        val shape = if (measured != null && menuShape is CornerBasedShape) {
            val size = Size(measured.menuBounds.width.toFloat(), measured.menuBounds.height.toFloat())
            val closedRadius = size.minDimension / 2f
            fun corner(end: Float) = with(density) { (closedRadius + (end - closedRadius) * reveal).toDp() }
            RoundedCornerShape(
                topStart = corner(menuShape.topStart.toPx(size, density)),
                topEnd = corner(menuShape.topEnd.toPx(size, density)),
                bottomStart = corner(menuShape.bottomStart.toPx(size, density)),
                bottomEnd = corner(menuShape.bottomEnd.toPx(size, density)),
            )
        } else menuShape
        Popup(popupPositionProvider = positionProvider, onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = focusable, dismissOnBackPress = true, dismissOnClickOutside = true)) {
            // Ordinary bounded measurement supports lazy/subcomposed content.
            // No intrinsic sizing is used anywhere in this popup container.
            Box(Modifier.padding(shadowSpace)) {
                Surface(
                    modifier = Modifier.graphicsLayer {
                        val frame = geometry?.frame(progress)
                        scaleX = frame?.scaleX ?: 1f
                        scaleY = frame?.scaleY ?: 1f
                        translationX = frame?.translationX ?: 0f
                        translationY = frame?.translationY ?: 0f
                        transformOrigin = frame?.origin ?: TransformOrigin.Center
                        alpha = if (frame == null) 0f else opacity
                    }.widthIn(min = 144.dp.coerceAtMost(maxWidth), max = maxWidth)
                        .width((width ?: maxWidth).coerceAtMost(maxWidth)).then(modifier),
                    shape = shape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = MenuDefaults.TonalElevation,
                    shadowElevation = MenuDefaults.ShadowElevation,
                ) {
                    Column(
                        modifier = Modifier.heightIn(max = maxHeight)
                            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .graphicsLayer { alpha = ((progress - .2f) / .8f).coerceIn(0f, 1f) }
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        content = { content() },
                    )
                }
            }
        }
    }
}

internal class MemohMenuPositionProvider(
    private val alignment: Alignment,
    private val ownerBounds: IntRect?,
    private val margin: Int,
    private val gap: Int,
    private val shadowPadding: Int = 0,
    private val onPosition: (MemohMenuGeometry) -> Unit = {},
) : PopupPositionProvider {
    var geometry: MemohMenuGeometry? = null
        private set
    val transformOrigin: TransformOrigin get() = geometry?.origin ?: TransformOrigin.Center
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val surfaceSize = IntSize((popupContentSize.width - shadowPadding * 2).coerceAtLeast(0),
            (popupContentSize.height - shadowPadding * 2).coerceAtLeast(0))
        val left = maxOf(margin, ownerBounds?.left ?: margin)
        val right = minOf(windowSize.width - margin, ownerBounds?.right ?: windowSize.width - margin)
        val aligned = alignment.align(surfaceSize, anchorBounds.size, layoutDirection)
        val x = (anchorBounds.left + aligned.x)
            .coerceIn(left, (right - surfaceSize.width).coerceAtLeast(left))
        val y = (anchorBounds.top + aligned.y - gap)
            .coerceAtLeast(margin)
        val calculated = MemohMenuGeometry(anchorBounds, IntRect(x, y, x + surfaceSize.width, y + surfaceSize.height))
        geometry = calculated
        onPosition(calculated)
        return IntOffset(x - shadowPadding, y - shadowPadding)
    }
}

/** Expressive groups share the same shapes and 2dp spacing as their menu items. */
@Composable
fun MemohMenuGroup(index: Int = 0, count: Int = 1, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(index, count),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 0.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

/** Compact typography with the official expressive selection and shape animation. */
@Composable
fun MemohMenuRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    selected: Boolean = false,
    selectable: Boolean = selected,
    accent: Color? = null,
    index: Int = 0,
    count: Int = 1,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MenuDefaults.itemShape(index, count)
    val label: @Composable () -> Unit = {
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
    val glyph: @Composable () -> Unit = {
        Icon(icon, null, Modifier.size(20.dp))
    }
    val supporting: (@Composable () -> Unit)? = subtitle?.takeIf(String::isNotBlank)?.let { value ->
        { Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis) }
    }
    if (selectable) {
        SelectableDropdownMenuItem(
            enabled = enabled,
            selected = selected,
            onClick = onClick,
            text = label,
            shapes = shapes,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = glyph,
            selectedLeadingIcon = glyph,
            supportingText = supporting,
            trailingContent = {
                Box(Modifier.size(20.dp)) {
                    if (selected) Icon(Icons.Filled.Check, null, Modifier.size(20.dp))
                }
            },
            colors = MenuDefaults.selectableItemColors(
                containerColor = colors.surfaceContainer,
                selectedContainerColor = colors.secondaryContainer,
                selectedTextColor = colors.onSecondaryContainer,
                selectedLeadingIconColor = colors.onSecondaryContainer,
                selectedTrailingContentColor = colors.primary,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        )
    } else {
        DropdownMenuItem(
            enabled = enabled,
            onClick = onClick,
            text = label,
            shape = shapes.shape,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = glyph,
            supportingText = supporting,
            colors = MenuDefaults.itemColors().copy(
                textColor = if (accent == colors.errorContainer) colors.error else colors.onSurface,
                leadingIconColor = if (accent == colors.errorContainer) colors.error else colors.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** A section label inside a [MemohPopupMenu]. */
@Composable
fun MemohMenuSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** A thin separator between groups in a [MemohPopupMenu]. */
@Composable
fun MemohMenuDivider() {
    Spacer(modifier = Modifier.height(6.dp))
}
