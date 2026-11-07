package com.example.claude.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.claude.data.Repo
import com.example.claude.data.UiPrefs
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class Palette(
    val bg: Color, val text: Color, val sub: Color, val accent: Color,
    val bubble: Color, val card: Color, val glass: Color, val dark: Boolean,
)

val Light = Palette(
    bg = Color(0xFFFAF9F5), text = Color(0xFF141413), sub = Color(0xFF73726C), accent = Color(0xFFD97757),
    bubble = Color(0xFFF0EEE6), card = Color(0xB3FFFFFF), glass = Color(0x66FFFFFF), dark = false,
)
val Dark = Palette(
    bg = Color(0xFF262624), text = Color(0xFFF5F4EE), sub = Color(0xFFA09F96), accent = Color(0xFFD97757),
    bubble = Color(0xFF30302E), card = Color(0x26FFFFFF), glass = Color(0x24FFFFFF), dark = true,
)

val LocalPalette = staticCompositionLocalOf { Light }
val LocalUi = staticCompositionLocalOf { UiPrefs() }
val P: Palette @Composable get() = LocalPalette.current

@Composable
fun ClaudeTheme(content: @Composable () -> Unit) {
    val ui by Repo.ui.collectAsState()
    val dark = when (ui.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val pal = (if (dark) Dark else Light).copy(accent = Color(ui.accent))
    CompositionLocalProvider(LocalPalette provides pal, LocalUi provides ui, content = content)
}
