package dev.memoh.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.compose.material3.MaterialShapes
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlin.math.roundToInt

internal fun SplashScreen.animateMemohExit() {
    setOnExitAnimationListener { splash ->
        val layer = splash.view
        val icon = splash.iconView
        if (!ValueAnimator.areAnimatorsEnabled() || icon.width == 0 || icon.height == 0) {
            splash.remove()
            return@setOnExitAnimationListener
        }

        val layerPosition = IntArray(2).also(layer::getLocationInWindow)
        val iconPosition = IntArray(2).also(icon::getLocationInWindow)
        val platformSplash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val mark = LaunchMark(
            context = layer.context,
            centerX = iconPosition[0] - layerPosition[0] + icon.width / 2f,
            centerY = iconPosition[1] - layerPosition[1] + icon.height / 2f,
            badgeSize = if (platformSplash) icon.width.toFloat() else layer.resources.getDimension(R.dimen.launch_badge_size),
            startShape = if (platformSplash) MaterialShapes.Square else MaterialShapes.Circle,
        )
        mark.setBounds(0, 0, layer.width, layer.height)
        mark.alpha = 0
        layer.overlay.add(mark)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                val handoff = (fraction / 0.18f).coerceIn(0f, 1f)
                icon.alpha = 1f - handoff
                mark.alpha = (255f * handoff).roundToInt()
                mark.setProgress(fraction)
                layer.alpha = 1f - ((fraction - 0.38f) / 0.62f).coerceIn(0f, 1f)
            }
            doOnEnd {
                layer.overlay.remove(mark)
                splash.remove()
            }
            start()
        }
    }
}

private class LaunchMark(
    context: Context,
    private val centerX: Float,
    private val centerY: Float,
    private val badgeSize: Float,
    startShape: RoundedPolygon,
) : Drawable() {
    private val mascotSize = badgeSize * context.resources.getDimension(R.dimen.launch_mascot_size) /
        context.resources.getDimension(R.dimen.launch_badge_size)
    private val mascot = requireNotNull(context.getDrawable(R.drawable.ic_launcher_foreground)).mutate()
    private val morph = Morph(startShape.normalized(), MaterialShapes.Cookie4Sided.normalized())
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.launcher_background) }
    private val spatial = PathInterpolator(0f, 0f, 0.2f, 1f)
    private var progress = 0f

    fun setProgress(fraction: Float) {
        progress = spatial.getInterpolation((fraction / 0.65f).coerceIn(0f, 1f))
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val diameter = badgeSize * (1f + 0.10f * progress)
        val y = centerY - badgeSize * 0.08f * progress
        canvas.save()
        canvas.translate(centerX - diameter / 2f, y - diameter / 2f)
        canvas.scale(diameter, diameter)
        canvas.drawPath(morph.toPath(progress, path), paint)
        canvas.restore()

        val radius = mascotSize * (1f + 0.08f * progress) / 2f
        mascot.setBounds((centerX - radius).roundToInt(), (y - radius).roundToInt(),
            (centerX + radius).roundToInt(), (y + radius).roundToInt())
        mascot.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        mascot.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        mascot.colorFilter = colorFilter
    }

    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
