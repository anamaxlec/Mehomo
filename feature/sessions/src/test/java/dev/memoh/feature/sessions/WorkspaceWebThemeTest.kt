package dev.memoh.feature.sessions

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWebThemeTest {
    @Test
    fun nightWorkspaceHasADarkReadingPlaneAndLegibleText() {
        val theme = WorkspaceWebTheme(darkColorScheme())
        assertTrue(theme.dark)
        assertTrue(theme.background.luminance() < .05f)
        assertTrue(theme.foreground.luminance() > .5f)
        assertTrue((theme.foreground.luminance() + .05f) / (theme.background.luminance() + .05f) > 7f)
    }

    @Test
    fun dayWorkspaceHasALightReadingPlaneAndLegibleText() {
        val theme = WorkspaceWebTheme(lightColorScheme())
        assertFalse(theme.dark)
        assertTrue(theme.background.luminance() > .8f)
        assertTrue(theme.foreground.luminance() < .1f)
        assertTrue((theme.background.luminance() + .05f) / (theme.foreground.luminance() + .05f) > 7f)
    }
}
