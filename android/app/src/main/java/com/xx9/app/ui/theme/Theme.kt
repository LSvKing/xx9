package com.xx9.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0F1216)
val Card = Color(0xFF181C24)
val Line = Color(0xFF232A35)
val Fg = Color(0xFFE6EAF0)
val Mut = Color(0xFF8B94A3)
val Acc = Color(0xFF2DD4BF)
val AccText = Color(0xFF06231F)
val CastBlock = Color(0xFFFF6B5B)
val Danger = Color(0xFFF0786B)

private val scheme = darkColorScheme(
    primary = Acc,
    onPrimary = AccText,
    background = Bg,
    onBackground = Fg,
    surface = Card,
    onSurface = Fg,
    surfaceVariant = Line,
    onSurfaceVariant = Mut,
    error = Danger,
    onError = Fg,
)

@Composable
fun Xx9Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
