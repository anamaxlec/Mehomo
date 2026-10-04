package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.feature.login.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun bothLoginButtonsAnimateTheirPageAndReturnToThePicker() {
        var step by mutableStateOf(LoginStep.ServerPicker)
        val positions = mutableMapOf<LoginStep, Float>()
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme { LoginStepContent(step) { page ->
            Box(Modifier.fillMaxSize().onGloballyPositioned { positions[page] = it.positionInRoot().x }) {
                when (page) {
                    LoginStep.ServerPicker -> ServerPickerScreen({ step = LoginStep.Cloud }, { step = LoginStep.SelfHosted })
                    LoginStep.Cloud -> CloudLoginScreen(LoginUiState(), {}, {}, {}, {}, { step = LoginStep.ServerPicker })
                    LoginStep.SelfHosted -> SelfHostedLoginScreen(LoginUiState(), {}, {}, {}, {}, { step = LoginStep.ServerPicker })
                    LoginStep.TeamPicker -> Unit
                }
            }
        } } }
        fun advance(time: Long) { compose.mainClock.advanceTimeBy(time); compose.waitForIdle() }
        for ((label, target) in listOf("使用 Memoh Cloud" to LoginStep.Cloud, "自托管服务器" to LoginStep.SelfHosted)) {
            compose.onNodeWithText(label).performClick()
            advance(64)
            assertTrue("$target must enter from the right", (positions[target] ?: 0f) > 0f)
            advance(600)
            assertEquals(0f, positions[target]!!, 1f)
            compose.onNodeWithText("返回").performClick()
            advance(64)
            assertTrue("Picker must enter from the left on return", (positions[LoginStep.ServerPicker] ?: 0f) < 0f)
            advance(600)
            compose.onNodeWithText("使用 Memoh Cloud").assertExists()
            assertEquals(0f, positions[LoginStep.ServerPicker]!!, 1f)
        }
    }
}
