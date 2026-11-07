package com.example.claude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val codeBg = Color(0x22888888)

private fun inline(s: String, color: Color, accent: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val e = s.indexOf("**", i + 2)
                if (e > 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, e)) }; i = e + 2 }
                else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val e = s.indexOf('`', i + 1)
                if (e > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = accent)) { append(s.substring(i + 1, e)) }
                    i = e + 1
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, onArtifact: ((String, String) -> Unit)? = null) {
    val color = P.text
    val ui = LocalUi.current
    val sc = ui.textScale
    val rf = when (ui.replyFont) { 0 -> FontFamily.Serif; 1 -> FontFamily.SansSerif; else -> FontFamily.Monospace }
    val parts = text.split("```")
    Column(modifier) {
        parts.forEachIndexed { idx, part ->
            if (idx % 2 == 1) {
                val lang = part.substringBefore('\n').trim().lowercase()
                val code = part.substringAfter('\n', part).trimEnd()
                if (onArtifact != null && idx < parts.lastIndex && lang in setOf("html", "svg") && code.length > 40) {
                    ArtifactBlock(lang, code) { onArtifact(lang, code) }
                } else BasicText(
                    code,
                    Modifier.padding(vertical = 6.dp).background(codeBg, RoundedCornerShape(12.dp))
                        .horizontalScroll(rememberScrollState()).padding(12.dp),
                    TextStyle(color, (13 * sc).sp, fontFamily = FontFamily.Monospace),
                )
            } else part.trim('\n').split("\n").forEach { raw ->
                val line = raw.trimEnd()
                when {
                    line.isBlank() -> {}
                    line.startsWith("#") -> BasicText(
                        inline(line.trimStart('#').trim(), color, P.accent),
                        Modifier.padding(top = 8.dp, bottom = 2.dp), TextStyle(color, (20 * sc).sp, FontWeight.Medium, fontFamily = rf))
                    line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> BasicText(
                        inline("•  " + line.trimStart().drop(2), color, P.accent),
                        Modifier.padding(start = 6.dp, top = 2.dp), TextStyle(color, (16 * sc).sp, lineHeight = 24.sp, fontFamily = rf))
                    else -> BasicText(inline(line, color, P.accent), Modifier.padding(top = 3.dp), TextStyle(color, (16 * sc).sp, lineHeight = 24.sp, fontFamily = rf))
                }
            }
        }
    }
}
