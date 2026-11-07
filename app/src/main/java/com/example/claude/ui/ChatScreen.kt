package com.example.claude.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.claude.agent.Shell
import com.example.claude.data.Attachment
import com.example.claude.data.ToolStep
import androidx.compose.ui.text.font.FontFamily
import com.example.claude.data.Models
import com.example.claude.data.Repo
import com.example.claude.data.Source
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.example.claude.data.QueuedSend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private val suggestions = listOf("Plan my day", "Explain something simply", "What's in the news today?", "Help me write an email")

@Composable
fun ChatScreen(ctl: ChatController, backdrop: Backdrop, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val model by Repo.model.collectAsState()
    val projects by Repo.projects.collectAsState()
    val project = ctl.projectId?.let { id -> projects.firstOrNull { it.id == id } }
    var input by remember { mutableStateOf("") }
    var inputBase by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var talk by remember { mutableStateOf(false) }
    var wantTalk by remember { mutableStateOf(false) }
    var artifact by remember { mutableStateOf<Pair<String, String>?>(null) }
    val list = rememberLazyListState()
    val last = ctl.msgs.lastOrNull()
    val queue by Repo.queue.collectAsState()
    val queuedHere = queue.filter { it.chatId == ctl.chatId }
    val allChats by Repo.chats.collectAsState()
    LaunchedEffect(allChats, ctl.chatId) { ctl.reloadIfChanged() }

    val voice = remember { VoiceInput(ctx, { t, _ -> input = (inputBase + " " + t).trim() }, { listening = it }) }
    DisposableEffect(Unit) { onDispose { voice.destroy(); Speaker.stop() } }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { if (wantTalk) { wantTalk = false; talk = true } else { inputBase = input; voice.start() } } else Toast.makeText(ctx, "Microphone permission is needed for voice input", Toast.LENGTH_SHORT).show()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch {
            uris.forEach { u ->
                val a = Attachments.import(ctx, u)
                if (a != null) ctl.addAttachment(a) else Toast.makeText(ctx, "Unsupported file or larger than 10 MB", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(ctl.msgs.size, last?.text?.length, ctl.status, ctl.error, ctl.limit, queuedHere.size) {
        val extras = (if (ctl.error != null) 1 else 0) + (if (ctl.limit != null) 1 else 0) + queuedHere.size
        if (ctl.msgs.isNotEmpty()) list.scrollToItem(ctl.msgs.size - 1 + extras)
    }

    Box(Modifier.fillMaxSize()) {
        if (ctl.msgs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Label("✺", color = P.accent, size = 44, bold = true)
                Spacer(Modifier.height(10.dp))
                Label(greeting(), size = 30, font = androidx.compose.ui.text.font.FontFamily.Serif)
                Label(if (project != null) "📁 ${project.name}" else "How can I help you today?", color = P.sub, size = 17, font = androidx.compose.ui.text.font.FontFamily.Serif)
                Spacer(Modifier.height(24.dp))
                suggestions.chunked(2).forEach { row ->
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s -> Chip(backdrop, s, false) { ctl.send(s) } }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(), list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = insetTop() + 76.dp, bottom = insetBottom() + 190.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(ctl.msgs.size) { i ->
                    val m = ctl.msgs[i]
                    val isLast = i == ctl.msgs.lastIndex
                    if (m.role == "user") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            m.atts.forEach { a -> if (a.mime.startsWith("image/")) Thumb(a.path) else FileChip(a) }
                            if (m.text.isNotBlank()) Box(Modifier.widthIn(max = 300.dp).background(P.bubble, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Label(m.text, size = 16)
                            }
                            if (!ctl.busy) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Label("Edit", Modifier.tap { input = ctl.beginEdit(i) }, P.sub, 13, true)
                                if (m.text.isNotBlank()) Label("Remember", Modifier.tap {
                                    Repo.addMemory(m.text.take(240)); Toast.makeText(ctx, "Saved to memory", Toast.LENGTH_SHORT).show()
                                }, P.sub, 13, true)
                            }
                        }
                    } else Column {
                        if (m.text.isEmpty()) Label(ctl.status ?: "✺ Thinking…", color = P.accent, font = androidx.compose.ui.text.font.FontFamily.Serif)
                        else {
                            Markdown(m.text, onArtifact = { l, c -> artifact = l to c })
                            if (isLast && ctl.busy && ctl.status != null) Label(ctl.status!!, color = P.accent, size = 13, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (m.tools.isNotEmpty()) ToolSteps(m.tools)
                        if (m.sources.isNotEmpty()) Sources(m.sources)
                        if (m.text.isNotEmpty() && !(ctl.busy && isLast)) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val key = "m$i-${m.time}"
                            Label("Copy", Modifier.tap { copy(ctx, m.text) }, P.sub, 13, true)
                            Label(if (Speaker.speakingKey == key) "■ Stop" else "🔊 Listen", Modifier.tap { Speaker.toggle(ctx, key, m.text) }, P.sub, 13, true)
                            if (isLast) Label("Retry", Modifier.tap { ctl.retry() }, P.sub, 13, true)
                        }
                    }
                }
                if (ctl.error != null) item { Label("⚠️ ${ctl.error}", color = Color(0xFFD64545), size = 14) }
                if (ctl.limit != null) item { LimitBanner(ctl, backdrop) }
                queuedHere.forEach { q -> item(key = q.id) { QueuedBanner(q, ctl) } }
            }
        }

        // header
        Row(Modifier.fillMaxWidth().padding(top = insetTop() + 10.dp, start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIcon(backdrop, "☰", onMenu)
            Spacer(Modifier.weight(1f))
            GlassButton(backdrop, {
                val i = Models.all.indexOfFirst { it.id == model }
                Repo.setModel(Models.all[(i + 1) % Models.all.size].id)
            }) { Label("Clause · ${Models.label(model)} ⇅", bold = true, size = 15) }
            Spacer(Modifier.weight(1f))
            GlassIcon(backdrop, "＋", { ctl.newChat(ctl.projectId) })
        }

        // composer
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .padding(start = 12.dp, end = 12.dp, bottom = insetBottom() + 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ctl.pending.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ctl.pending.forEach { a ->
                    Row(Modifier.glass(backdrop, Capsule()).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label((if (a.mime.startsWith("image/")) "🖼 " else "📄 ") + a.name.take(20), size = 13)
                        Label("  ✕", Modifier.tap { ctl.removeAttachment(a) }, P.sub, 13, true)
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(backdrop, "🌐 Search", ctl.webSearch) { ctl.webSearch = !ctl.webSearch }
                Chip(backdrop, "📎 Attach", false) { picker.launch("*/*") }
                Chip(backdrop, "🎨 Imagine", ctl.imagine) { ctl.imagine = !ctl.imagine }
                Chip(backdrop, "🛠 Device", ctl.agent) {
                    if (!ctl.agent && !Shell.available()) Toast.makeText(ctx, "Set up Shizuku first: Settings → Device control", Toast.LENGTH_LONG).show()
                    else ctl.agent = !ctl.agent
                }
                Chip(backdrop, "🎙 Talk", false) {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) talk = true
                    else { wantTalk = true; micPerm.launch(Manifest.permission.RECORD_AUDIO) }
                }
                Chip(backdrop, if (listening) "⏹ Stop" else "🎤 Voice", listening) {
                    if (listening) voice.stop()
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { inputBase = input; voice.start() }
                    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassField(backdrop, input, { input = it }, if (project != null) "Message in ${project.name}" else if (ctl.msgs.isEmpty()) "How can I help you today?" else "Reply to Claude…", Modifier.weight(1f))
                GlassIcon(
                    backdrop, if (ctl.busy) "■" else "↑",
                    { if (ctl.busy) ctl.stop() else { if (listening) voice.stop(); ctl.send(input); input = "" } },
                    Modifier.size(52.dp), tint = P.accent.copy(alpha = 0.92f),
                    enabled = ctl.busy || input.isNotBlank() || ctl.pending.isNotEmpty(),
                )
            }
        }
        artifact?.let { a -> ArtifactViewer(a.first, a.second, backdrop) { artifact = null } }
        if (talk) VoiceModeOverlay(ctl, backdrop) { talk = false }
        ctl.approval?.let { ApprovalSheet(it, backdrop, ctl::resolveApproval) }
    }
}

@Composable
private fun Sources(sources: List<Source>) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        Label("🌐 ${sources.size} sources ${if (open) "▲" else "▼"}", Modifier.tap { open = !open }, P.accent, 13, true)
        if (open) sources.forEach { s ->
            Label("• ${s.title.ifBlank { s.url }}", Modifier.padding(top = 4.dp).tap {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, P.sub, 13, false)
        }
    }
}

@Composable
private fun Thumb(path: String) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull()
        }
    }
    bmp?.let { Image(it, null, Modifier.size(140.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
}

@Composable
private fun FileChip(a: Attachment) {
    Box(Modifier.background(P.bubble, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Label("📄 ${a.name.take(28)}", size = 14)
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening"
}

internal fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Claude", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private fun human(s: Long) = when { s < 90 -> "${s}s"; s < 5400 -> "${s / 60}m"; else -> "${s / 3600}h" }

/** Shown when the API says the usage/rate limit is hit: one tap queues the prompt to send itself later. */
@Composable
private fun LimitBanner(ctl: ChatController, backdrop: Backdrop) {
    val ctx = LocalContext.current
    val e = ctl.limit ?: return
    Column(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(20.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(when (e.limitKind) {
            "credit" -> "You've used up your available credit or usage."
            "rate" -> "Rate limit reached."
            else -> "Claude is overloaded right now."
        }, bold = true)
        Label("Auto-send this prompt for me when it's available again:", color = P.sub, size = 13)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            e.retryAfterSec?.let { s -> Chip(backdrop, "⏰ When ready (${human(s)})", true) { ctl.queueAutoSend(ctx, s + 2) } }
            Chip(backdrop, "In 1 min", false) { ctl.queueAutoSend(ctx, 60) }
            Chip(backdrop, "In 1 hour", false) { ctl.queueAutoSend(ctx, 3600) }
            Chip(backdrop, "In 5 hours", false) { ctl.queueAutoSend(ctx, 18000) }
            Chip(backdrop, "Tomorrow 8:00", false) { ctl.queueAutoSend(ctx, secondsUntilTomorrowMorning()) }
        }
        Chip(backdrop, "Retry now", false) { ctl.retry() }
    }
}

@Composable
private fun QueuedBanner(q: QueuedSend, ctl: ChatController) {
    val ctx = LocalContext.current
    val fmt = remember { SimpleDateFormat("EEE h:mm a", Locale.getDefault()) }
    Row(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(20.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("⏳ Queued · auto-sends ${fmt.format(Date(q.at))}", Modifier.weight(1f), size = 14)
        Label("Cancel", Modifier.tap { ctl.cancelQueued(ctx, q.id) }.padding(6.dp), P.accent, 14, true)
    }
}

/** Live list of what Claude did on the device this turn; tap a row to see its output. */
@Composable
private fun ToolSteps(steps: List<ToolStep>) {
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { s ->
            val icon = when (s.status) { "pending" -> "⏳"; "done" -> "✓"; "denied" -> "🚫"; "blocked" -> "⛔"; else -> "✗" }
            Column(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(12.dp)).tap { open = if (open == s.id) null else s.id }.padding(10.dp)) {
                Label("$icon  ${s.summary}", size = 13, font = FontFamily.Monospace)
                if (open == s.id && s.result.isNotBlank()) Label(s.result.take(900), Modifier.padding(top = 6.dp), P.sub, 12, false, FontFamily.Monospace)
            }
        }
    }
}




