package com.xx9.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0F1216)
val Surface = Color(0xFF181C24)
val Card = Surface
val Line = Color(0xFF232A35)
val Fg = Color(0xFFE6EAF0)
val Mut = Color(0xFF8B94A3)
val Acc = Color(0xFF2DD4BF)
val AccSoft = Color(0x1F2DD4BF) // ~12%
val AccOn = Color(0xFF06231F)
val AccText = AccOn
val Danger = Color(0xFFF0786B)
val CastBlock = Color(0xFFFF6B5B)
val CastSoft = Color(0x24FF6B5B) // ~14%
val CastDisabled = Color(0xFF5B6473)

private val scheme = darkColorScheme(
    primary = Acc,
    onPrimary = AccOn,
    background = Bg,
    onBackground = Fg,
    surface = Surface,
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
