package dev.memoh.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A floating pill that sits in a screen corner over the content.
 *
 * A fully rounded surface at 8dp elevation, inset from the screen edges rather
 * than docked to them, so the content behind stays partly visible and the
 * control reads as floating over the list instead of terminating it.
 */
@Composable
fun MemohFloatingBar(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    elevation: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = elevation,
        shadowElevation = elevation,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * A round floating action button for a screen corner.
 *
 * Same surface treatment as [MemohSectionBar] so the two read as one family.
 *
 * [collapsed] shrinks it in step with the bar — the two sit on one line, so if
 * only the bar moved the row would visibly come apart. Flare does the same
 * (56dp → 40dp) and keeps the icon at a fixed 20dp: the button shrinks around
 * it rather than the icon shrinking inside the button.
 *
 * The Material FAB owns both the circular surface and its shadow. An external
 * shadow modifier is outside the FAB's minimum-touch-target layout and can use
 * different bounds when the visual size drops below 48dp.
 */
@Composable
fun MemohCornerFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    collapsed: Boolean = false,
) {
    val size by animateDpAsState(
        targetValue = if (collapsed) 40.dp else 56.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "cornerFabSize",
    )
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Makes a floating control collapse while the user scrolls and return when
 * they scroll back.
 *
 * This is Material 3 Expressive's own `floatingToolbarVerticalNestedScroll`,
 * not a hand-rolled `firstVisibleItemIndex` listener: it participates in the
 * nested-scroll chain, so it reacts to drag and fling the way the system does
 * and composes correctly with other scroll effects.
 *
 * Apply to the scrolling container; [expanded] flips false past the threshold,
 * which the caller uses to shrink or hide the control.
 */
@Composable
fun Modifier.collapseOnScroll(
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
): Modifier {
    // `floatingToolbarVerticalNestedScroll` is a *member extension* on
    // FloatingToolbarDefaults, so both receivers are needed: the object supplies
    // the function, the Modifier is its subject.
    val base = this
    return with(FloatingToolbarDefaults) {
        base.floatingToolbarVerticalNestedScroll(
            expanded = expanded,
            onExpand = onExpand,
            onCollapse = onCollapse,
        )
    }
}
