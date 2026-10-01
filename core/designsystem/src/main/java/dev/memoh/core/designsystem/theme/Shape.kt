package dev.memoh.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Expressive corner ladder. Large radii are the visible signature of Material 3
 * Expressive, so the top of the scale is deliberately rounder than baseline M3:
 * the composer capsule and bottom sheets land on extraLarge (28dp).
 */
internal val MemohShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
