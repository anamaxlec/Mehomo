package dev.memoh.core.designsystem.component

import androidx.compose.runtime.staticCompositionLocalOf
import dev.memoh.core.model.ErrorAction

val LocalErrorActionHandler = staticCompositionLocalOf<((ErrorAction) -> Unit)?> { null }
