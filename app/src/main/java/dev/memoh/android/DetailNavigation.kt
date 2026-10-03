package dev.memoh.android

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.RoundedCornerCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.memoh.core.designsystem.motion.MemohMotion
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class NavigationGeometry(val distance: Int, val margin: Int, val scrim: Color)

@Composable
internal fun navigationGeometry(): NavigationGeometry = with(LocalDensity.current) {
    NavigationGeometry(MemohMotion.NavigationDistance.roundToPx(), MemohMotion.PredictiveDisplayMargin.roundToPx(),
        Color.Black.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.8f else 0.2f))
}

/** Observes the existing NavHost handler; it never consumes a back event. */
@Stable
private class DetailMotion(val geometry: NavigationGeometry, val rtl: Boolean) {
    var predictiveEntryId by mutableStateOf<String?>(null)
    var edge by mutableIntStateOf(NavigationEvent.EDGE_LEFT)
    var tracking by mutableStateOf(false)
    var sawProgress by mutableStateOf(false)
    private val signedDistance get() = geometry.distance * if (rtl) -1 else 1
    private fun isPreview(entryId: String) = predictiveEntryId == entryId
    fun enter(entryId: String, pop: Boolean, sectionReturn: SectionReturn?): EnterTransition = when {
        isPreview(entryId) -> MemohMotion.predictivePopEnter(geometry.distance, geometry.scrim)
        pop && sectionReturn?.entryId == entryId && sectionReturn.motion in listOf(SectionMotion.Forward, SectionMotion.Backward) ->
            MemohMotion.horizontalSwap(sectionReturn.motion == SectionMotion.Forward).targetContentEnter
        pop -> MemohMotion.detailPopEnter(signedDistance)
        else -> MemohMotion.detailEnter(signedDistance)
    }
    fun exit(entryId: String, pop: Boolean, sectionReturn: SectionReturn?): ExitTransition = when {
        isPreview(entryId) -> MemohMotion.predictivePopExit(edge == NavigationEvent.EDGE_LEFT, geometry.margin)
        pop && sectionReturn?.entryId == entryId && sectionReturn.motion in listOf(SectionMotion.Forward, SectionMotion.Backward) ->
            MemohMotion.horizontalSwap(sectionReturn.motion == SectionMotion.Forward).initialContentExit
        pop -> MemohMotion.detailPopExit(signedDistance)
        else -> MemohMotion.detailExit(signedDistance)
    }
}

/** Keeps the predictive spec through both commit and cancellation, until the entry settles. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun MemohNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    sectionReturn: SectionReturn? = null,
    builder: NavGraphBuilder.() -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val geometry = navigationGeometry()
    val motion = remember(geometry, rtl) { DetailMotion(geometry, rtl) }
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val scope = rememberCoroutineScope()
    DisposableEffect(dispatcher, navController, motion) {
        // Main.immediate also observes a start/progress/commit delivered in a single frame.
        val observer = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            dispatcher?.transitionState?.collect { state ->
                if (state is NavigationEventTransitionState.InProgress &&
                    state.direction == NavigationEventTransitionState.TRANSITIONING_BACK &&
                    state.latestEvent.swipeEdge != NavigationEvent.EDGE_NONE) {
                    if (!motion.tracking) {
                        motion.predictiveEntryId = navController.currentBackStackEntry?.id
                        motion.sawProgress = false
                    }
                    if (state.latestEvent.progress > 0f) motion.sawProgress = true
                    motion.edge = state.latestEvent.swipeEdge
                    motion.tracking = true
                } else {
                    motion.tracking = false
                }
            }
        }
        onDispose { observer.cancel() }
    }
    LaunchedEffect(motion.tracking, motion.predictiveEntryId) {
        if (!motion.tracking && motion.predictiveEntryId != null) {
            if (!motion.sawProgress) {
                // NavHost 2.9.6 prepares entries on start, but its cancel animation
                // only runs after a progress event. A start-only cancellation has
                // no animation to finish, leaving the entries at STARTED.
                withFrameNanos { }
                val navigator = navController.navigatorProvider.getNavigator<ComposeNavigator>("composable")
                val entries = navigator.backStack.value
                if (entries.lastOrNull()?.id == motion.predictiveEntryId &&
                    navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.STARTED) {
                    entries.takeLast(2).forEach(navigator::onTransitionComplete)
                }
            }
            navController.currentBackStackEntryFlow.flatMapLatest { it.lifecycle.currentStateFlow }
                .first { it == Lifecycle.State.RESUMED }
            motion.predictiveEntryId = null
        }
    }
    val corners = rememberDeviceCornerShape()
    val navigator = navController.navigatorProvider.getNavigator<ComposeNavigator>("composable")
    var entryFlow by remember(navController) { mutableStateOf<StateFlow<List<NavBackStackEntry>>?>(null) }
    // NavHost attaches the navigator while composing its graph.
    SideEffect { if (entryFlow == null) entryFlow = navigator.backStack }
    val entries = entryFlow?.collectAsState()?.value
    CompositionLocalProvider(LocalNavigationCorners provides corners, LocalNavigationEntries provides entries,
        LocalPredictiveEntry provides motion.predictiveEntryId) {
        NavHost(navController, startDestination, modifier.clipToBounds(),
            enterTransition = { motion.enter(initialState.id, pop = false, sectionReturn) },
            exitTransition = { motion.exit(initialState.id, pop = false, sectionReturn) },
            popEnterTransition = { motion.enter(initialState.id, pop = true, sectionReturn) },
            popExitTransition = { motion.exit(initialState.id, pop = true, sectionReturn) },
            builder = builder)
    }
}

private val LocalNavigationCorners = staticCompositionLocalOf<Shape> { RectangleShape }
private val LocalNavigationEntries = staticCompositionLocalOf<List<NavBackStackEntry>?> { null }
private val LocalPredictiveEntry = staticCompositionLocalOf<String?> { null }

/** Keeps a cancelled preview's last opacity when a return interrupts its rewind. */
@Composable
internal fun AnimatedContentScope.predictiveNavigationAlpha(isPreview: Boolean, returning: Boolean): State<Float> {
    val previewAlpha = transition.animateFloat(
        transitionSpec = { tween(MemohMotion.NAVIGATION_MS, easing = LinearEasing) }, label = "predictiveOpacity",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val completion = remember(isPreview, returning) { Animatable(previewAlpha.value) }
    LaunchedEffect(isPreview, returning) {
        if (isPreview && returning) {
            completion.animateTo(0f, tween((completion.value * MemohMotion.NAVIGATION_MS).roundToInt(), easing = LinearEasing))
        }
    }
    return when {
        !isPreview -> remember { mutableFloatStateOf(1f) }
        returning -> completion.asState()
        else -> previewAlpha
    }
}

/** Clips each entry before NavHost scales it, matching Flare's device-corner decorator. */
internal fun NavGraphBuilder.memohComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        val corners = LocalNavigationCorners.current
        val returning = LocalNavigationEntries.current?.none { it.id == entry.id } == true
        val previewAlpha = predictiveNavigationAlpha(LocalPredictiveEntry.current == entry.id, returning)
        // NavHost rewinds an interrupted entrance, but can draw another forward
        // frame before that rewind starts. A popped, unfinished entry must not
        // appear again while its old entrance is being cancelled.
        val interruptedEntrance = remember(returning) { returning && transition.currentState == EnterExitState.PreEnter }
        Box(Modifier.fillMaxSize().graphicsLayer {
            shape = corners; clip = true; alpha = if (interruptedEntrance) 0f else previewAlpha.value
        }) { content(entry) }
    }
}

@Composable
internal fun rememberDeviceCornerShape(): Shape {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var insets by remember(view) { mutableStateOf(ViewCompat.getRootWindowInsets(view)) }
    LaunchedEffect(view, configuration) {
        withFrameNanos { }
        insets = ViewCompat.getRootWindowInsets(view)
    }
    fun radius(position: Int) = with(density) { (insets?.getRoundedCorner(position)?.radius ?: 0).toDp() }
    return AbsoluteRoundedCornerShape(
        topLeft = radius(RoundedCornerCompat.POSITION_TOP_LEFT),
        topRight = radius(RoundedCornerCompat.POSITION_TOP_RIGHT),
        bottomRight = radius(RoundedCornerCompat.POSITION_BOTTOM_RIGHT),
        bottomLeft = radius(RoundedCornerCompat.POSITION_BOTTOM_LEFT),
    )
}
