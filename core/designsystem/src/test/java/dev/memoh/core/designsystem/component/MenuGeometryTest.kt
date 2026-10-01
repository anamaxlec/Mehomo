package dev.memoh.core.designsystem.component

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MenuGeometryTest {
    @Test
    fun compactModelButtonIsTheOpeningRectangle() {
        val geometry = MemohMenuGeometry(IntRect(260, 600, 300, 630), IntRect(60, 218, 300, 618))
        assertBounds(geometry, 0f, geometry.anchorBounds)
        assertBounds(geometry, 1f, geometry.menuBounds)
    }

    @Test
    fun constrainedMenuStillStartsAtTheButtonOutsideItsFinalBounds() {
        val geometry = MemohMenuGeometry(IntRect(345, 600, 385, 640), IntRect(144, 200, 384, 628))
        assertBounds(geometry, 0f, geometry.anchorBounds)
        assertBounds(geometry, 1f, geometry.menuBounds)
    }

    @Test
    fun wideBotButtonAndOddPixelSizesKeepTheFullOpeningBounds() {
        val geometry = MemohMenuGeometry(IntRect(24, 101, 313, 154), IntRect(24, 142, 271, 298))
        assertBounds(geometry, 0f, geometry.anchorBounds)
        assertBounds(geometry, 1f, geometry.menuBounds)
    }

    @Test
    fun halfwayAndReversedMotionUseTheSameContinuousPath() {
        val geometry = MemohMenuGeometry(IntRect(240, 600, 280, 640), IntRect(40, 328, 280, 628))
        for (progress in listOf(0f, .25f, .5f, .75f, 1f, .75f, .5f, .25f, 0f)) {
            val anchor = geometry.anchorBounds
            val menu = geometry.menuBounds
            assertBounds(
                geometry, progress,
                left = anchor.left + (menu.left - anchor.left) * progress,
                top = anchor.top + (menu.top - anchor.top) * progress,
                right = anchor.right + (menu.right - anchor.right) * progress,
                bottom = anchor.bottom + (menu.bottom - anchor.bottom) * progress,
            )
        }
    }

    private fun assertBounds(geometry: MemohMenuGeometry, progress: Float, expected: IntRect) =
        assertBounds(geometry, progress, expected.left.toFloat(), expected.top.toFloat(), expected.right.toFloat(), expected.bottom.toFloat())

    private fun assertBounds(geometry: MemohMenuGeometry, progress: Float, left: Float, top: Float, right: Float, bottom: Float) {
        val frame = geometry.frame(progress)
        val menu = geometry.menuBounds
        val pivotX = menu.left + menu.width * frame.origin.pivotFractionX
        val pivotY = menu.top + menu.height * frame.origin.pivotFractionY
        // Apply the same graphics transform to all four edges. A pivot alone
        // cannot reach a button outside the menu bounds; translation must agree.
        assertEquals(left, pivotX + (menu.left - pivotX) * frame.scaleX + frame.translationX, .001f)
        assertEquals(right, pivotX + (menu.right - pivotX) * frame.scaleX + frame.translationX, .001f)
        assertEquals(top, pivotY + (menu.top - pivotY) * frame.scaleY + frame.translationY, .001f)
        assertEquals(bottom, pivotY + (menu.bottom - pivotY) * frame.scaleY + frame.translationY, .001f)
    }
}
