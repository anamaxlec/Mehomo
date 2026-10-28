package dev.memoh.android

import android.content.pm.ShortcutManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinShortcutRequestTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aSessionShortcutCanBeRequestedFromTheLauncher() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ShortcutManager::class.java)
        assumeTrue("Launcher does not support pinned shortcuts", manager.isRequestPinShortcutSupported)
        compose.runOnIdle {
            assertTrue(LauncherShortcuts.pin(context, "fixture-account", "fixture-bot", "fixture-session", "固定的测试会话"))
        }
    }
}
