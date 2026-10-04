package dev.memoh.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.memoh.core.designsystem.motion.MemohMotion
import dev.memoh.feature.login.LoginStep

@Composable
internal fun LoginStepContent(step: LoginStep, content: @Composable (LoginStep) -> Unit) {
    val distance = navigationGeometry().distance * if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedContent(step, Modifier.fillMaxSize(), transitionSpec = {
        if (targetState == LoginStep.ServerPicker) {
            (MemohMotion.detailPopEnter(distance) togetherWith MemohMotion.detailPopExit(distance)).apply { targetContentZIndex = -1f }
        } else MemohMotion.detailEnter(distance) togetherWith MemohMotion.detailExit(distance)
    }, contentKey = { it }, label = "loginStep") { content(it) }
}
