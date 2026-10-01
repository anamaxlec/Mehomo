package dev.memoh.core.designsystem.component

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemohMenuPositionProviderTest {
    @Test
    fun shadowPaddingDoesNotMoveTheSurfaceAwayFromTheButton() {
        val padded = MemohMenuPositionProvider(Alignment.BottomStart, IntRect(40, 480, 360, 680), 16, 12, shadowPadding = 12)
        val anchor = IntRect(52, 600, 92, 640)
        val position = padded.calculatePosition(anchor, IntSize(400, 800), LayoutDirection.Ltr, IntSize(184, 174))
        assertEquals(IntOffset(40, 466), position)
        assertEquals(.125f, padded.transformOrigin.pivotFractionX, .001f)
        assertEquals(142f / 150f, padded.transformOrigin.pivotFractionY, .001f)
    }

    @Test
    fun aMenuConstrainedByTheRightEdgeStillGrowsFromItsTrigger() {
        val constrained = provider(Alignment.BottomStart)
        constrained.calculatePosition(IntRect(260, 600, 330, 640), IntSize(400, 800), LayoutDirection.Ltr, IntSize(180, 150))
        assertTrue(constrained.transformOrigin.pivotFractionX > .5f)
        assertEquals(115f / 180f, constrained.transformOrigin.pivotFractionX, .001f)
    }

    @Test
    fun leftControlMenuOverlapsItsAnchor() {
        val position = provider(Alignment.BottomStart).calculatePosition(
            IntRect(52, 600, 92, 640), IntSize(400, 800),
            LayoutDirection.Ltr, IntSize(160, 150),
        )
        assertEquals(IntOffset(52, 478), position)
    }

    @Test
    fun rightControlKeepsMenuInsideComposer() {
        val position = provider(Alignment.BottomStart).calculatePosition(
            IntRect(260, 600, 330, 640), IntSize(400, 800),
            LayoutDirection.Ltr, IntSize(180, 150),
        )
        assertEquals(IntOffset(180, 478), position)
    }

    @Test
    fun modelMenuAlignsToTriggerEnd() {
        val position = provider(Alignment.BottomEnd).calculatePosition(
            IntRect(100, 600, 280, 640), IntSize(400, 800),
            LayoutDirection.Ltr, IntSize(240, 300),
        )
        assertEquals(IntOffset(40, 328), position)
    }

    @Test
    fun rightToLeftAndShortWindowKeepMenuVisible() {
        val position = provider(Alignment.BottomStart).calculatePosition(
            IntRect(260, 200, 330, 240), IntSize(400, 400),
            LayoutDirection.Rtl, IntSize(180, 300),
        )
        assertEquals(IntOffset(150, 16), position)
    }

    @Test
    fun visibleWindowHeightDoesNotPushMenuAwayFromBottomControl() {
        // Android reports the visible frame height without the system bars,
        // while the anchor below is in window coordinates.
        val position = MemohMenuPositionProvider(
            alignment = Alignment.BottomStart,
            ownerBounds = IntRect(42, 1937, 1038, 2340),
            margin = 42,
            gap = 31,
        ).calculatePosition(
            IntRect(557, 2265, 788, 2339), IntSize(1080, 2219),
            LayoutDirection.Ltr, IntSize(466, 289),
        )
        assertEquals(IntOffset(557, 2019), position)
    }

    private fun provider(alignment: Alignment) = MemohMenuPositionProvider(
        alignment = alignment,
        ownerBounds = IntRect(40, 480, 360, 680),
        margin = 16,
        gap = 12,
    )
}
