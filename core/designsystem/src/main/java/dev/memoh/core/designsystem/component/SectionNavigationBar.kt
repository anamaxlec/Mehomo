package dev.memoh.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** One entry in [MemohSectionBar]. */
data class MemohNavItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * The app's top-level section switcher, floating in a screen corner.
 *
 * Composed from Material 3 Expressive's [ShortNavigationBarItem] rather than the
 * `ShortNavigationBar` container: the container fills the width it is given and
 * reserves system-bar insets, which suits a bar docked to the bottom edge and is
 * wrong for one floating in a corner. The *items* carry the expressive motion —
 * the shape-morph indicator and the icon/label treatment — so taking the items
 * and supplying our own pill keeps that motion and lets the bar size itself.
 *
 * Collapsing follows Flare's rule: **only the selected item survives**, as an
 * icon. That is what makes the bar readable at a glance while scrolling — it
 * answers "where am I", which is the only question worth answering when the user
 * is mid-scroll and not choosing anything.
 *
 * The collapse transition is Flare's own structure, not a size animation:
 * an [AnimatedContent] between two fully laid-out states — everything with
 * labels, or just the selected item — with the pill, each item and each icon
 * tied together by shared elements. The two states each have a *fixed* size, so
 * the surface's shadow outline is never redrawn mid-flight; the shared-element
 * overlay interpolates the whole rendered surface (shadow included) between
 * them. That is what kills the trailing grey slab a `animateContentSize`-driven
 * size animation drags behind it: there, the shadow outline lags the layout
 * bounds every frame.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MemohSectionBar(
    items: List<MemohNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
) {
    val shape = RoundedCornerShape(percent = 50)

    SharedTransitionLayout(modifier) {
        AnimatedContent(
            targetState = collapsed,
            label = "sectionBarState",
        ) { barCollapsed ->
            Surface(
                modifier = Modifier
                    .sharedElement(
                        rememberSharedContentState(key = "bar-surface"),
                        animatedVisibilityScope = this@AnimatedContent,
                    )
                    // `Modifier.shadow` rather than `graphicsLayer`'s
                    // shadowElevation: only the former draws an outline that
                    // follows the shape. The layer property renders the shadow
                    // from the layout bounds on several Android versions, which
                    // leaves grey wedges outside the rounded ends — the same
                    // defect the corner FAB had.
                    .shadow(elevation = 6.dp, shape = shape, clip = false),
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                // Tonal only: the shadow is drawn by the graphicsLayer above, and
                // asking Surface for one as well would double it.
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Row(
                    // No vertical padding on purpose: the item's own height
                    // (56dp expanded, 40dp collapsed) must equal the corner FAB's
                    // size in the same state, or the two controls on one line sit
                    // on different baselines. Only the sides keep breathing room.
                    modifier = Modifier.padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { index, item ->
                        val selected = index == selectedIndex
                        // Flare's rule: collapsed means composition, not opacity —
                        // non-selected items are simply not laid out in that state,
                        // and the shared elements carry the transition.
                        if (!barCollapsed || selected) {
                            ShortNavigationBarItem(
                                selected = selected,
                                onClick = { onSelect(index) },
                                modifier = Modifier.sharedElement(
                                    rememberSharedContentState(key = "bar-item-$index"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                ),
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .sharedElement(
                                                rememberSharedContentState(key = "bar-icon-$index"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                            )
                                            .size(22.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.label,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                                label = if (barCollapsed) null else { { Text(item.label) } },
                                interactionSource = remember { MutableInteractionSource() },
                            )
                        }
                    }
                }
            }
        }
    }
}
