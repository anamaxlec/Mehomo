package dev.memoh.core.designsystem.component

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntRect

/** Map the whole menu surface from the trigger's bounds to its final bounds. */
internal data class MemohMenuGeometry(val anchorBounds: IntRect, val menuBounds: IntRect) {
    private val anchorCenterX = (anchorBounds.left + anchorBounds.right) / 2f
    private val anchorCenterY = (anchorBounds.top + anchorBounds.bottom) / 2f
    private val menuCenterX = (menuBounds.left + menuBounds.right) / 2f
    private val menuCenterY = (menuBounds.top + menuBounds.bottom) / 2f
    val origin = TransformOrigin(
        if (menuBounds.width == 0) .5f else ((anchorCenterX - menuBounds.left) / menuBounds.width).coerceIn(0f, 1f),
        if (menuBounds.height == 0) .5f else ((anchorCenterY - menuBounds.top) / menuBounds.height).coerceIn(0f, 1f),
    )

    fun frame(progress: Float): MemohMenuFrame {
        val fraction = progress.coerceIn(0f, 1f)
        val startX = anchorBounds.width.toFloat() / menuBounds.width.coerceAtLeast(1)
        val startY = anchorBounds.height.toFloat() / menuBounds.height.coerceAtLeast(1)
        val pivotX = menuBounds.left + menuBounds.width * origin.pivotFractionX
        val pivotY = menuBounds.top + menuBounds.height * origin.pivotFractionY
        val closedCenterX = pivotX + (menuCenterX - pivotX) * startX
        val closedCenterY = pivotY + (menuCenterY - pivotY) * startY
        return MemohMenuFrame(
            scaleX = startX + (1f - startX) * fraction,
            scaleY = startY + (1f - startY) * fraction,
            translationX = (anchorCenterX - closedCenterX) * (1f - fraction),
            translationY = (anchorCenterY - closedCenterY) * (1f - fraction),
            origin = origin,
        )
    }
}

internal data class MemohMenuFrame(
    val scaleX: Float, val scaleY: Float,
    val translationX: Float, val translationY: Float,
    val origin: TransformOrigin,
)
