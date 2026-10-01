package dev.memoh.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Composer menus share the input field's horizontal boundaries. */
val LocalMemohPopupBounds = staticCompositionLocalOf<IntRect?> { null }

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
    content: @Composable () -> Unit,
) {
    // The transition state outlives the toggle so the exit animation has
    // something to run against; the Popup is composed only while it is on either
    // side of the transition, because an invisible Popup still captures input.
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded

    if (visibility.currentState || visibility.targetState) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val bounds = LocalMemohPopupBounds.current
        val maxWidth = with(density) { bounds?.width?.toDp() }?.coerceAtMost(248.dp) ?: 248.dp
        val positionProvider = remember(alignment, bounds, density) {
            MemohMenuPositionProvider(
                alignment = alignment,
                ownerBounds = bounds,
                margin = with(density) { 16.dp.roundToPx() },
                gap = with(density) { 12.dp.roundToPx() },
            )
        }
        // Expressive motion, not a hand-tuned spring: the spatial spec carries
        // the size and position change and the effects spec carries the fade,
        // which is the pairing that makes M3E surfaces feel like they have mass.
        val motion = MaterialTheme.motionScheme
        val spatialFloat = motion.defaultSpatialSpec<Float>()
        val spatialOffset = motion.defaultSpatialSpec<IntOffset>()
        val fade = motion.defaultEffectsSpec<Float>()

        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        ) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(fade) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = spatialFloat,
                        transformOrigin = TransformOrigin(0.5f, 1f),
                    ) +
                    slideInVertically(animationSpec = spatialOffset, initialOffsetY = { it / 12 }),
                exit = fadeOut(fade) +
                    scaleOut(
                        targetScale = 0.96f,
                        animationSpec = spatialFloat,
                        transformOrigin = TransformOrigin(0.5f, 1f),
                    ) +
                    slideOutVertically(animationSpec = spatialOffset, targetOffsetY = { it / 16 }),
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 144.dp.coerceAtMost(maxWidth), max = maxWidth)
                        .then(if (width == null) Modifier.width(IntrinsicSize.Max)
                            else Modifier.width(width.coerceAtMost(maxWidth)))
                        .then(modifier),
                    // M3E menu shape: a large rounded surface, which is the
                    // expressive counterpart to the composer's own capsule.
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = maxHeight)
                            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState())
                                else Modifier)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        content()
                    }
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
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val left = maxOf(margin, ownerBounds?.left ?: margin)
        val right = minOf(windowSize.width - margin, ownerBounds?.right ?: windowSize.width - margin)
        val aligned = alignment.align(popupContentSize, anchorBounds.size, layoutDirection)
        val x = (anchorBounds.left + aligned.x)
            .coerceIn(left, (right - popupContentSize.width).coerceAtLeast(left))
        val y = (anchorBounds.top + aligned.y - gap)
            .coerceAtLeast(margin)
        return IntOffset(x, y)
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
