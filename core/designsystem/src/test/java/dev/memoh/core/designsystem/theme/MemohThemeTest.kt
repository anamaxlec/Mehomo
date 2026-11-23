package dev.memoh.core.designsystem.theme

import dev.memoh.core.model.MemohAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemohThemeTest {
    @Test fun `every accent pairs light and dark inversePrimary with the opposite surface`() {
        MemohAccent.entries.forEach { accent ->
            val light = memohColorScheme(darkTheme = false, accent = accent)
            val dark = memohColorScheme(darkTheme = true, accent = accent)
            assertEquals("Light inversePrimary should use dark primary for $accent", dark.primary, light.inversePrimary)
            assertEquals("Dark inversePrimary should use light primary for $accent", light.primary, dark.inversePrimary)
            assertNotEquals("Primary and inversePrimary should differ for $accent", light.primary, light.inversePrimary)
        }
    }
}
