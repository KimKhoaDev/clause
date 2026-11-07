package com.example.claude.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.Backdrop

private fun page(code: String): String =
    if (code.contains("<html", true) || code.contains("<!doctype", true)) code
    else """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#fff}body{display:flex;align-items:center;justify-content:center}svg{max-width:100%;max-height:100%;height:auto}</style>
</head><body>$code</body></html>"""

/** Title from <title>, an SVG <title>, the first <h1>, or a default. */
fun artifactTitle(lang: String, code: String): String {
    val m = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(code)
        ?: Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(code)
    val t = m?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
    return t.ifBlank { if (lang == "svg") "Illustration" else "Artifact" }.take(60)
}

/**
 * Sandboxed preview. Scripts run (HTML artifacts need them) but the network is blocked, file/content access is off,
 * so an artifact can't load remote code or send data anywhere.
 */
@Composable
fun WebPreview(code: String, interactive: Boolean, modifier: Modifier, lang: String = "html", onWeb: ((WebView) -> Unit)? = null) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = lang == "html"
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = interactive
                if (interactive) { settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false }
                else { settings.useWideViewPort = true; settings.loadWithOverviewMode = true }
                setBackgroundColor(0xFFFFFFFF.toInt())
                loadDataWithBaseURL(null, page(code), "text/html", "utf-8", null)
                onWeb?.invoke(this)
            }
        },
        modifier = modifier,
    )
}

/** What a closed ```html / ```svg block becomes inside a reply: a live thumbnail plus title, like Claude's artifact card. */
@Composable
fun ArtifactBlock(lang: String, code: String, onOpen: () -> Unit) {
    val title = remember(code) { artifactTitle(lang, code) }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.sub.copy(alpha = 0.25f), shape)) {
        Box(Modifier.fillMaxWidth().height(if (lang == "svg") 280.dp else 210.dp)) {
            WebPreview(code, false, Modifier.fillMaxSize(), lang)
            Box(Modifier.fillMaxSize().tap(onClick = onOpen))
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(title, bold = true, size = 16, font = FontFamily.Serif)
                Label(if (lang == "svg") "Image · SVG" else "Artifact · HTML", color = P.sub, size = 12)
            }
            Label("Open ›", Modifier.tap(onClick = onOpen).padding(6.dp), P.accent, 14, true)
        }
    }
}

@Composable
fun ArtifactViewer(lang: String, code: String, backdrop: Backdrop, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var reload by remember { mutableStateOf(0) }
    val title = remember(code) { artifactTitle(lang, code) }
    val web = remember { arrayOfNulls<WebView>(1) }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(P.bg).padding(top = insetTop(), bottom = insetBottom())) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassIcon(backdrop, "✕", onClose)
            Column(Modifier.weight(1f)) {
                Label(title, bold = true, size = 17, font = FontFamily.Serif)
                Label(if (lang == "svg") "SVG image" else "HTML artifact", color = P.sub, size = 12)
            }
            Chip(backdrop, "Preview", tab == 0) { tab = 0 }
            Chip(backdrop, "Code", tab == 1) { tab = 1 }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(backdrop, "↻ Reload", false) { tab = 0; reload++ }
            Chip(backdrop, "Copy", false) { copy(ctx, code) }
            Chip(backdrop, "Share", false) {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), null))
            }
            Chip(backdrop, "Download ${if (lang == "svg") ".svg" else ".html"}", false) { download(ctx, lang, code, title) }
            Chip(backdrop, "Save PNG", false) { savePng(ctx, web[0]) }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(16.dp)).background(P.card)) {
            if (tab == 0) androidx.compose.runtime.key(reload) { WebPreview(code, true, Modifier.fillMaxSize(), lang) { web[0] = it } }
            else BasicText(code, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(14.dp),
                TextStyle(P.text, 12.sp, fontFamily = FontFamily.Monospace))
        }
    }
}

private fun download(ctx: Context, lang: String, code: String, title: String) {
    if (Build.VERSION.SDK_INT < 29) { Toast.makeText(ctx, "Saving needs Android 10 or newer", Toast.LENGTH_SHORT).show(); return }
    runCatching {
        val ext = if (lang == "svg") "svg" else "html"
        val name = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "claude" }.take(40)
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.Downloads.MIME_TYPE, if (lang == "svg") "image/svg+xml" else "text/html")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Claude")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(code.toByteArray()) }
        Toast.makeText(ctx, "Saved to Downloads/Claude", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(ctx, "Couldn't save file", Toast.LENGTH_SHORT).show() }
}

private fun savePng(ctx: Context, w: WebView?) {
    if (w == null || w.width == 0 || w.height == 0) { Toast.makeText(ctx, "Open the Preview tab first", Toast.LENGTH_SHORT).show(); return }
    if (Build.VERSION.SDK_INT < 29) { Toast.makeText(ctx, "Saving needs Android 10 or newer", Toast.LENGTH_SHORT).show(); return }
    runCatching {
        val bmp = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        w.draw(Canvas(bmp))
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "claude-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Claude")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Toast.makeText(ctx, "Saved to Pictures/Claude", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(ctx, "Couldn't save image", Toast.LENGTH_SHORT).show() }
}




