package dev.memoh.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.feature.settings.SettingsViewModel

/**
 * The single activity. Edge-to-edge is enabled so the app draws under the system
 * bars; screens apply their own insets through Scaffold.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings: SettingsViewModel = hiltViewModel()
            val themeMode by settings.themeMode.collectAsState()
            val accent by settings.accent.collectAsState()

            val darkTheme = when (themeMode) {
                dev.memoh.core.model.ThemeMode.System -> isSystemInDarkTheme()
                dev.memoh.core.model.ThemeMode.Light -> false
                dev.memoh.core.model.ThemeMode.Dark -> true
            }

            MemohTheme(darkTheme = darkTheme, accent = accent) {
                MemohApp()
            }
        }
    }
}
