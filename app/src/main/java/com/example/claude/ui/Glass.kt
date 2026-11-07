package com.example.claude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

/** Liquid-glass surface from Kyant0/AndroidLiquidGlass. */
@Composable
fun Modifier.glass(backdrop: Backdrop, shape: Shape, tint: Color = Color.Unspecified): Modifier {
    val ui = LocalUi.current
    val surface = if (tint == Color.Unspecified) Color.White.copy(alpha = if (P.dark) ui.tint * 0.35f else ui.tint) else tint
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (ui.vibrancy) vibrancy()
            if (ui.blur > 0.5f) blur(ui.blur.dp.toPx())
            if (ui.lensH > 0.5f && ui.lensAmt > 0.5f) lens(ui.lensH.dp.toPx(), ui.lensAmt.dp.toPx())
        },
        onDrawSurface = { drawRect(surface) },
    )
}

@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)

@Composable
fun GlassButton(
    backdrop: Backdrop, onClick: () -> Unit, modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified, enabled: Boolean = true, content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.glass(backdrop, Capsule(), tint).alpha(if (enabled) 1f else 0.5f)
            .tap(enabled, onClick).defaultMinSize(44.dp, 44.dp).padding(horizontal = 14.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = P.text, size: Int = 15, bold: Boolean = false, font: FontFamily? = null) {
    BasicText(text, modifier, TextStyle(color, (size * LocalUi.current.textScale).sp, if (bold) FontWeight.SemiBold else FontWeight.Normal, fontFamily = font))
}

@Composable
fun GlassIcon(backdrop: Backdrop, glyph: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, enabled: Boolean = true) {
    GlassButton(backdrop, onClick, modifier.size(44.dp), tint, enabled) {
        Label(glyph, color = if (tint == Color.Unspecified) P.text else Color.White, size = 18, bold = true)
    }
}

@Composable
fun Chip(backdrop: Backdrop, text: String, selected: Boolean, onClick: () -> Unit) {
    GlassButton(backdrop, onClick, tint = if (selected) P.accent.copy(alpha = 0.85f) else Color.Unspecified) {
        Label(text, color = if (selected) Color.White else P.text, size = 14, bold = selected)
    }
}

@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(50.dp, 30.dp).tap { onChange(!on) }
            .background(if (on) P.accent else P.sub.copy(alpha = 0.35f), Capsule()).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(24.dp).background(Color.White, Capsule())) }
}

