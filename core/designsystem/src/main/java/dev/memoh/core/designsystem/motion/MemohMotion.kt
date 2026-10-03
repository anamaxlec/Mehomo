package dev.memoh.core.designsystem.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.unveilIn
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * The app's motion vocabulary.
 *
 * Controls and panels combine spatial movement with opacity effects. Detail
 * navigation follows Flare's Android timing: a short fade within a longer
 * spatial transition, with a separate scale-and-unveil predictive back preview.
 *
 * These are hand-written specs rather than `MaterialTheme.motionScheme` because
 * the scheme's specs are not available outside a composable, and navigation
 * transitions are configured at graph-construction time.
 *
 * Controls and panels follow two timing rules:
 *  - enter is slower than exit, because arriving content should be watched and
 *    leaving content should get out of the way;
 *  - the outgoing element fades faster than it moves, so the two never look
 *    like they are fighting for the same space.
 */
object MemohMotion {

    /** The emphasised decelerate curve: fast in, gentle settle. M3E's default. */
    val EmphasisedDecelerate: Easing = LinearOutSlowInEasing

    /** Arriving content: 300ms, the scheme's default tier. */
    const val ENTER_MS = 300

    /** Leaving content: shorter, so it clears the way. */
    const val EXIT_MS = 220

    /** In-place changes (a row swapping state): quicker than a full transition. */
    const val SWAP_MS = 200

    fun <T> enterSpec(): FiniteAnimationSpec<T> = tween(ENTER_MS, easing = EmphasisedDecelerate)

    fun <T> exitSpec(): FiniteAnimationSpec<T> = tween(EXIT_MS, easing = EmphasisedDecelerate)

    // -- navigation ---------------------------------------------------------

    // Adapted from DimensionDev/Flare's Router.kt (AGPL-3.0); see THIRD_PARTY_NOTICES.md.
    const val NAVIGATION_MS = 450
    val NavigationDistance = 96.dp
    val PredictiveDisplayMargin = 8.dp
    private const val PREDICTIVE_TARGET_SCALE = 0.85f
    private val NavigationSpatialEasing = PathEasing(Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    })
    private val PredictiveEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

    private fun navigationSpec() = tween<IntOffset>(NAVIGATION_MS, easing = NavigationSpatialEasing)
    private fun detailFadeOut() = fadeOut(keyframes {
        durationMillis = NAVIGATION_MS
        1f at 0
        1f at 35 using LinearEasing
        0f at 118
    })

    /** [slideDistance] is 96dp in pixels, signed for the layout direction. */
    fun detailEnter(slideDistance: Int): EnterTransition =
        slideInHorizontally(animationSpec = navigationSpec()) { slideDistance } + fadeIn(keyframes {
            durationMillis = NAVIGATION_MS
            0f at 0
            0f at 50 using LinearEasing
            1f at 133
        })

    fun detailExit(slideDistance: Int): ExitTransition =
        slideOutHorizontally(animationSpec = navigationSpec()) { -slideDistance }

    fun detailPopEnter(slideDistance: Int): EnterTransition =
        slideInHorizontally(animationSpec = navigationSpec()) { -slideDistance }

    fun detailPopExit(slideDistance: Int): ExitTransition =
        slideOutHorizontally(animationSpec = navigationSpec()) { slideDistance } + detailFadeOut()

    fun predictivePopEnter(enteringOffset: Int, scrimColor: Color): EnterTransition =
        unveilIn(initialColor = scrimColor, matchParentSize = true, animationSpec = keyframes {
            durationMillis = NAVIGATION_MS
            scrimColor at 0 using LinearEasing
            scrimColor.copy(alpha = 0f) at NAVIGATION_MS
        }) + scaleIn(tween(NAVIGATION_MS, easing = PredictiveEasing), initialScale = 0.95f) +
            slideInHorizontally(tween(NAVIGATION_MS, easing = PredictiveEasing)) { -enteringOffset }

    /** Geometry only; predictive entry opacity is driven by the seekable content scope. */
    fun predictivePopExit(fromLeft: Boolean, displayMargin: Int): ExitTransition =
        scaleOut(tween(NAVIGATION_MS, easing = PredictiveEasing), targetScale = PREDICTIVE_TARGET_SCALE) +
            slideOutHorizontally(tween(NAVIGATION_MS, easing = PredictiveEasing)) { fullWidth ->
                if (fromLeft) (fullWidth * (1f - PREDICTIVE_TARGET_SCALE) / 2f).toInt() - displayMargin else 0
            }

    // -- in-place -----------------------------------------------------------

    /**
     * A top-level section replacing another.
     *
     * A fade with a small upward drift and a slight scale, which is the
     * expressive counterpart to a plain cross-fade: the incoming section rises
     * into place, so the change has a direction even though nothing slides.
     */
    fun sectionEnter(): EnterTransition =
        fadeIn(enterSpec()) +
            slideInVertically(animationSpec = enterSpec()) { height -> height / 16 } +
            scaleIn(animationSpec = enterSpec(), initialScale = 0.97f)

    fun sectionExit(): ExitTransition =
        fadeOut(exitSpec()) +
            scaleOut(animationSpec = exitSpec(), targetScale = 0.99f)

    /**
     * A menu or sheet unfolding from the control that opened it.
     *
     * [originX] is 0 for a menu anchored to the start edge and 1 for the end, so
     * the card grows out of the corner nearest its trigger.
     */
    fun menuEnter(originX: Float): EnterTransition =
        fadeIn(enterSpec()) +
            scaleIn(
                animationSpec = enterSpec(),
                initialScale = 0.9f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(originX, 1f),
            ) +
            slideInVertically(animationSpec = enterSpec()) { height -> height / 12 }

    fun menuExit(originX: Float): ExitTransition =
        fadeOut(exitSpec()) +
            scaleOut(
                animationSpec = exitSpec(),
                targetScale = 0.95f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(originX, 1f),
            ) +
            slideOutVertically(animationSpec = exitSpec()) { height -> height / 16 }

    /**
     * A list row appearing (a new session, a newly loaded page).
     *
     * Slides from the start edge rather than the bottom: rows arrive in a list,
     * and horizontal entry reads as "added to this list" instead of "fell in
     * from off-screen".
     */
    fun rowEnter(index: Int = 0): EnterTransition =
        fadeIn(tween(ENTER_MS, delayMillis = (index.coerceAtMost(6) * 30), easing = EmphasisedDecelerate)) +
            slideInHorizontally(
                animationSpec = tween(ENTER_MS, delayMillis = (index.coerceAtMost(6) * 30), easing = EmphasisedDecelerate),
            ) { width -> width / 8 }

    fun rowExit(): ExitTransition =
        fadeOut(exitSpec()) +
            slideOutHorizontally(animationSpec = exitSpec()) { width -> width / 8 }

    /** A bottom-anchored panel rising into place (the composer's trays). */
    fun panelEnter(): EnterTransition =
        fadeIn(enterSpec()) + slideInVertically(animationSpec = enterSpec()) { it / 4 }

    fun panelExit(): ExitTransition =
        fadeOut(exitSpec()) + slideOutVertically(animationSpec = exitSpec()) { it / 6 }

    // -- horizontal swaps ---------------------------------------------------

    /**
     * One horizontal pane replacing another, with a direction.
     *
     * Used for the search field replacing the title and for switching between
     * the chats / files / schedules panes. The direction comes from the caller
     * (segment order, or opening vs closing search) so the motion agrees with
     * where the user's attention is moving.
     *
     * The outgoing pane travels a shorter distance than the incoming one and
     * fades faster: it should clear out of the way, not compete for the space.
     */
    fun horizontalSwap(forward: Boolean): androidx.compose.animation.ContentTransform {
        val enter = slideInHorizontally(animationSpec = enterSpec()) { width ->
            if (forward) width / 3 else -width / 6
        } + fadeIn(tween(ENTER_MS, delayMillis = 60, easing = EmphasisedDecelerate))

        val exit = slideOutHorizontally(animationSpec = exitSpec()) { width ->
            if (forward) -width / 6 else width / 3
        } + fadeOut(tween(EXIT_MS, easing = EmphasisedDecelerate))

        return enter togetherWith exit
    }

    /**
     * A control appearing or disappearing in place (the search icon).
     *
     * Scales from slightly small rather than growing from zero: a control that
     * pops from nothing reads as a glitch, one that settles into size reads as
     * having been there.
     */
    fun controlEnter(): EnterTransition =
        fadeIn(enterSpec()) + scaleIn(animationSpec = enterSpec(), initialScale = 0.8f)

    fun controlExit(): ExitTransition =
        fadeOut(exitSpec()) + scaleOut(animationSpec = exitSpec(), targetScale = 0.8f)
}
