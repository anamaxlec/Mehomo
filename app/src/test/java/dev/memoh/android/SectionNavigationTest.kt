package dev.memoh.android

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionNavigationTest {
    @Test fun `all tab pairs follow their displayed positions`() {
        val orders = listOf(
            listOf(MainSection.Chats, MainSection.Terminal, MainSection.Desktop, MainSection.Profile),
            listOf(MainSection.Profile, MainSection.Schedules, MainSection.Chats, MainSection.Memory),
            listOf(MainSection.Files, MainSection.Profile, MainSection.Browser, MainSection.Chats),
        )
        for (order in orders) for ((leftIndex, left) in order.withIndex()) for ((rightIndex, right) in order.withIndex()) {
            val expected = when { leftIndex == rightIndex -> SectionMotion.None; leftIndex < rightIndex -> SectionMotion.Forward; else -> SectionMotion.Backward }
            assertEquals("$order: $left → $right", expected, tabMotion(left, right, order, false))
            val mirrored = when (expected) { SectionMotion.Forward -> SectionMotion.Backward; SectionMotion.Backward -> SectionMotion.Forward; else -> expected }
            assertEquals(mirrored, tabMotion(left, right, order, true))
        }
    }
    @Test fun `an unlisted feature does not invent a tab position`() {
        assertEquals(SectionMotion.None, tabMotion(MainSection.Memory, MainSection.Chats, listOf(MainSection.Chats, MainSection.Profile), false))
    }
}
