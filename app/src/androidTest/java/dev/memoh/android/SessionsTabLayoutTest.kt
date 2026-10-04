package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.feature.sessions.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionsTabLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun scheduleTabSwitchFitsNarrowWidthAndLargeText() {
        var tab by mutableStateOf(BotTab.Chats)
        compose.setContent { MemohTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    SessionsScreen(SessionsUiState(initialized = true, tab = tab), {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {},
                        onSelectTab = { tab = it }, scheduleContent = { Text("Schedule pane") })
                }
            }
        } }
        compose.onNodeWithText("日程").performClick()
        compose.onNodeWithText("Schedule pane").assertExists()
        compose.onNodeWithText("对话").performClick()
        compose.onNodeWithText("Schedule pane").assertDoesNotExist()
    }
}
