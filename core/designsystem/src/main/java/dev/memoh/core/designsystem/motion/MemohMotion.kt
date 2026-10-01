package dev.memoh.core.designsystem.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset

/**
 * The app's motion vocabulary.
 *
 * Material 3 Expressive describes motion as two channels that always run
 * together: a *spatial* channel carrying position and size, and an *effects*
 * channel carrying opacity. Keeping them separate is what lets a transition read
 * as one object moving rather than a cross-fade between two.
 *
 * These are hand-written specs rather than `MaterialTheme.motionScheme` because
 * the scheme's specs are not available outside a composable, and navigation
 * transitions are configured at graph-construction time. The durations and
 * curves here match the scheme's `default` tier (medium duration, emphasised
 * decelerate) so a hand-written transition and a scheme-driven one feel like the
 * same app.
 *
 * Two rules the whole file follows:
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

    /**
     * Pushing to a detail screen: the new screen slides in from the end edge
     * while the list slides a little the same way, so the list appears to be
     * pushed back rather than replaced.
     *
     * The outgoing screen moves only a third of the distance: full-width motion
     * for both makes the transition feel like a carousel instead of a push.
     */
    fun detailEnter(): EnterTransition =
        slideInHorizontally(animationSpec = enterSpec()) { full -> full / 4 } +
            fadeIn(enterSpec())

    fun detailExit(): ExitTransition =
        slideOutHorizontally(animationSpec = exitSpec()) { full -> -full / 8 } +
            fadeOut(exitSpec())

    /** Popping back: the reverse, with the returning screen arriving from behind. */
    fun detailPopEnter(): EnterTransition =
        slideInHorizontally(animationSpec = enterSpec()) { full -> -full / 8 } +
            fadeIn(enterSpec())

    fun detailPopExit(): ExitTransition =
        slideOutHorizontally(animationSpec = exitSpec()) { full -> full / 4 } +
            fadeOut(exitSpec())

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
