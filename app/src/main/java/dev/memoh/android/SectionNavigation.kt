package dev.memoh.android

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigationevent.NavigationEvent
import dev.memoh.core.designsystem.motion.MemohMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The motion belongs to the destination, including when it re-enters a transition. */
internal enum class SectionMotion { None, Forward, Backward, Push, Pop }

internal data class SectionDestination(val section: MainSection, val motion: SectionMotion)

internal fun sectionTransition(motion: SectionMotion, slideDistance: Int): ContentTransform = when (motion) {
    SectionMotion.Push -> MemohMotion.detailEnter(slideDistance) togetherWith MemohMotion.detailExit(slideDistance)
    SectionMotion.Pop -> (MemohMotion.detailPopEnter(slideDistance) togetherWith MemohMotion.detailPopExit(slideDistance)).apply { targetContentZIndex = -1f }
    SectionMotion.Forward -> MemohMotion.horizontalSwap(forward = true)
    SectionMotion.Backward -> MemohMotion.horizontalSwap(forward = false)
    SectionMotion.None -> fadeIn(MemohMotion.enterSpec()) togetherWith fadeOut(MemohMotion.exitSpec())
}

/** Keeps the shell's feature pages seekable without changing the floating tab layout. */
@Composable
internal fun SectionContent(
    destination: SectionDestination,
    backDestination: SectionDestination?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    rtl: Boolean = false,
    content: @Composable (SectionDestination) -> Unit,
) {
    // Motion metadata must not make the same page a different transition state.
    val state = remember { SeekableTransitionState(destination.section) }
    val transition = rememberTransition(state, label = "sectionContent")
    var predicting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var preview by remember { mutableStateOf(destination.section) }
    var predictiveEdge by remember { mutableStateOf<Int?>(null) }
    val geometry = navigationGeometry()
    val corners = rememberDeviceCornerShape()
    val interruptedEntrance = remember(destination) {
        state.targetState.takeIf {
            destination.motion == SectionMotion.Pop && predictiveEdge == null &&
                state.currentState == destination.section && it != destination.section
        }
    }
    PredictiveBackHandler(enabled = backDestination != null) { events ->
        val target = backDestination
        try {
            events.collect { event ->
                if (target != null) {
                    preview = target.section
                    progress = event.progress
                    predictiveEdge = event.swipeEdge
                    predicting = true
                }
            }
            if (target != null) onBack()
        } catch (_: CancellationException) {
            // The destination stays unchanged; the effect below rewinds the preview.
        } finally {
            predicting = false
        }
    }
    if (predicting) {
        LaunchedEffect(progress, preview) { state.seekTo(progress, preview) }
    } else {
        LaunchedEffect(destination) {
            if (state.currentState != destination.section) {
                state.animateTo(destination.section)
            } else {
                if (state.fraction > 0f) {
                    val duration = (state.fraction * transition.totalDurationNanos / 1_000_000).toInt()
                    animate(state.fraction, 0f, animationSpec = tween(duration)) { value, _ ->
                        launch { state.seekTo(value) }
                    }
                }
                state.snapTo(destination.section)
            }
            predictiveEdge = null
        }
    }
    transition.AnimatedContent(modifier = modifier, contentKey = { it },
        transitionSpec = {
            if (predictiveEdge != null) {
                (MemohMotion.predictivePopEnter(geometry.distance, geometry.scrim) togetherWith
                    MemohMotion.predictivePopExit(predictiveEdge == NavigationEvent.EDGE_LEFT, geometry.margin))
                    .apply { targetContentZIndex = -1f }
            } else sectionTransition(destination.motion, geometry.distance * if (rtl) -1 else 1)
        }) { target ->
        Box(Modifier.fillMaxSize().graphicsLayer {
            shape = corners; clip = true; alpha = if (target == interruptedEntrance) 0f else 1f
        }) { content(SectionDestination(target, destination.motion)) }
    }
}

internal fun tabMotion(from: MainSection, to: MainSection, order: List<MainSection>, rtl: Boolean): SectionMotion {
    val start = order.indexOf(from)
    val end = order.indexOf(to)
    if (start < 0 || end < 0 || start == end) return SectionMotion.None
    return if ((end > start) != rtl) SectionMotion.Forward else SectionMotion.Backward
}
