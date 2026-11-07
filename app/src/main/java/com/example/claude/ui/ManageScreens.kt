package com.example.claude.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.example.claude.agent.Shell
import com.example.claude.agent.ShizukuState
import com.example.claude.agent.ShizukuStatus
import com.example.claude.data.AgentPrefs
import com.example.claude.data.Risk
import com.example.claude.data.UiPrefs
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.example.claude.ui.glass.LiquidSlider
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.claude.data.Sync
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.claude.data.Chat
import com.example.claude.data.Models
import com.example.claude.data.Project
import com.example.claude.data.Repo
import com.example.claude.data.Styles
import com.kyant.backdrop.Backdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Centered card over a dimmed scrim. */
@Composable
fun Sheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).tap(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth().imePadding().background(P.bg, RoundedCornerShape(28.dp)).tap { }
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(title, bold = true, size = 17)
        content()
    }
}

// ------------------------------------------------------------------ history

@Composable
fun HistoryScreen(ctl: ChatController, backdrop: Backdrop, pad: PaddingValues, openChat: () -> Unit) {
    val ctx = LocalContext.current
    val chats by Repo.chats.collectAsState()
    val projects by Repo.projects.collectAsState()
    var q by remember { mutableStateOf("") }
    var menuId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val shown = chats.filter { q.isBlank() || it.title.contains(q, true) || it.msgs.any { m -> m.text.contains(q, true) } }
        .sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.updated })
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    val menuChat = menuId?.let { id -> chats.firstOrNull { it.id == id } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, pad.calculateTopPadding(), 16.dp, pad.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { GlassField(backdrop, q, { q = it }, "Search chats", Modifier.fillMaxWidth(), singleLine = true) }
            if (shown.isEmpty()) item { Label("No chats yet.", color = P.sub, modifier = Modifier.padding(top = 20.dp)) }
            items(shown, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(20.dp)).tap { ctl.open(c.id); openChat() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label((if (c.pinned) "📌 " else "") + c.title, bold = true)
                        Label(c.msgs.lastOrNull()?.text?.replace("\n", " ")?.take(90).orEmpty(), color = P.sub, size = 13)
                        val proj = projects.firstOrNull { it.id == c.projectId }?.let { "📁 ${it.name} · " }.orEmpty()
                        Label(proj + fmt.format(Date(c.updated)), color = P.sub, size = 11)
                    }
                    Label("⋯", size = 22, bold = true, modifier = Modifier.tap { menuId = c.id }.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }

        if (menuChat != null && !renaming) Sheet({ menuId = null }) {
            Label(menuChat.title, bold = true, size = 18)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(backdrop, "✏️ Rename", false) { newName = menuChat.title; renaming = true }
                Chip(backdrop, if (menuChat.pinned) "📌 Unpin" else "📌 Pin", false) { Repo.patchChat(menuChat.id) { it.copy(pinned = !it.pinned) }; menuId = null }
                Chip(backdrop, "⤴ Export", false) {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, menuChat.title).putExtra(Intent.EXTRA_TEXT, exportText(menuChat)), null))
                }
            }
            if (projects.isNotEmpty()) {
                Label("Move to project", color = P.sub, size = 13)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(backdrop, "None", menuChat.projectId == null) { Repo.patchChat(menuChat.id) { it.copy(projectId = null) } }
                    projects.forEach { p -> Chip(backdrop, p.name, menuChat.projectId == p.id) { Repo.patchChat(menuChat.id) { it.copy(projectId = p.id) } } }
                }
            }
            Chip(backdrop, "🗑 Delete chat", false) {
                Repo.deleteChat(menuChat.id); if (ctl.chatId == menuChat.id) ctl.newChat(); menuId = null
            }
        }
        if (menuChat != null && renaming) Sheet({ renaming = false }) {
            Label("Rename chat", bold = true, size = 18)
            GlassField(backdrop, newName, { newName = it }, "Title", Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(backdrop, { renaming = false }, Modifier.weight(1f)) { Label("Cancel", bold = true) }
                GlassButton(backdrop, { Repo.patchChat(menuChat.id) { it.copy(title = newName.trim()) }; renaming = false; menuId = null },
                    Modifier.weight(1f), tint = P.accent.copy(alpha = 0.92f), enabled = newName.isNotBlank()) { Label("Save", color = Color.White, bold = true) }
            }
        }
    }
}

// ------------------------------------------------------------------ projects

@Composable
fun ProjectsScreen(ctl: ChatController, backdrop: Backdrop, pad: PaddingValues, openChat: () -> Unit) {
    val projects by Repo.projects.collectAsState()
    val chats by Repo.chats.collectAsState()
    var editing by remember { mutableStateOf<Project?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, pad.calculateTopPadding(), 16.dp, pad.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassButton(backdrop, { editing = Project(UUID.randomUUID().toString(), "", "") }, Modifier.fillMaxWidth(), tint = P.accent.copy(alpha = 0.9f)) {
                    Label("＋  New project", color = Color.White, bold = true)
                }
            }
            if (projects.isEmpty()) item { Label("Projects keep chats together and give Claude standing instructions, like a persona or the context of a task.", color = P.sub) }
            items(projects, key = { it.id }) { p ->
                val mine = chats.filter { it.projectId == p.id }.sortedByDescending { it.updated }
                Column(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("📁 ${p.name}", bold = true, size = 17)
                    Label(p.instructions.take(140).ifBlank { "No instructions" }, color = P.sub, size = 13)
                    Label("${mine.size} chats", color = P.accent, size = 12, bold = true)
                    mine.take(3).forEach { c -> Label("• ${c.title}", size = 14, modifier = Modifier.tap { ctl.open(c.id); openChat() }.padding(vertical = 2.dp)) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(backdrop, "New chat", true) { ctl.newChat(p.id); openChat() }
                        Chip(backdrop, "Edit", false) { editing = p }
                        Chip(backdrop, "Delete", false) { Repo.deleteProject(p.id); if (ctl.projectId == p.id) ctl.newChat() }
                    }
                }
            }
        }
        editing?.let { e ->
            var name by remember(e.id) { mutableStateOf(e.name) }
            var ins by remember(e.id) { mutableStateOf(e.instructions) }
            Sheet({ editing = null }) {
                Label(if (e.name.isEmpty()) "New project" else "Edit project", bold = true, size = 20)
                GlassField(backdrop, name, { name = it }, "Project name", Modifier.fillMaxWidth(), singleLine = true)
                GlassField(backdrop, ins, { ins = it }, "Instructions for Claude in this project", Modifier.fillMaxWidth(), maxLines = 8)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(backdrop, { editing = null }, Modifier.weight(1f)) { Label("Cancel", bold = true) }
                    GlassButton(backdrop, { Repo.upsertProject(e.copy(name = name.trim(), instructions = ins.trim(), updated = System.currentTimeMillis())); editing = null },
                        Modifier.weight(1f), tint = P.accent.copy(alpha = 0.92f), enabled = name.isNotBlank()) { Label("Save", color = Color.White, bold = true) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
fun SettingsScreen(backdrop: Backdrop, pad: PaddingValues, needPerms: () -> Unit, onActivity: () -> Unit, afterSignOut: () -> Unit) {
    val model by Repo.model.collectAsState()
    val style by Repo.style.collectAsState()
    val key by Repo.apiKey.collectAsState()
    val memories by Repo.memories.collectAsState()
    val memoryOn by Repo.memoryOn.collectAsState()
    val webDefault by Repo.webDefault.collectAsState()
    val ctx = LocalContext.current
    var instr by remember { mutableStateOf(Repo.instructions.value) }
    var newMemory by remember { mutableStateOf("") }
    var newRoot by remember { mutableStateOf("") }
    val agentPrefs by Repo.agent.collectAsState()
    val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    val picked: (android.net.Uri?) -> Unit = { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            Repo.setSyncUri(u.toString()); Sync.syncSoon(true)
        }
    }
    val createSync = rememberLauncherForActivityResult(object : ActivityResultContracts.CreateDocument("application/json") {
        override fun createIntent(context: Context, input: String) = super.createIntent(context, input).addFlags(rw)
    }, picked)
    val openSync = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>) = super.createIntent(context, input).addFlags(rw)
    }, picked)
    val syncUri by Repo.syncUri.collectAsState()
    val lastSync by Repo.lastSync.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
            .padding(16.dp, pad.calculateTopPadding(), 16.dp, pad.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("Account") {
            Label(Repo.activeProvider().name + " key  " + (key?.let { "••••" + it.takeLast(4) } ?: "—"), color = P.sub)
            GlassButton(backdrop, { Repo.setApiKey(null); afterSignOut() }) { Label("Sign out of this provider", bold = true) }
        }
        Section("AI providers") { ProvidersSection(backdrop) }
        Section("AI providers") { ProvidersSection(backdrop) }
        Section("Device control (Shizuku)") {
            val st = ShizukuState.status
            Label(st.label, color = if (st == ShizukuStatus.Ready) Color(0xFF3E9B63) else P.sub, bold = true, size = 14)
            Label("Clause runs commands through Shizuku, which you start over Wireless debugging. No root and no USB cable.", color = P.sub, size = 13)
            when (st) {
                ShizukuStatus.NotInstalled -> Chip(backdrop, "Get Shizuku", true) { ShizukuState.openShizuku(ctx) }
                ShizukuStatus.NotRunning -> {
                    Label("1. Settings → Developer options → turn on Wireless debugging\n2. Open Shizuku → Pairing → enter the pairing code shown by Wireless debugging\n3. Tap Start in Shizuku (needs Wi-Fi; start it again after a reboot)", color = P.sub, size = 13)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(backdrop, "Developer options", false) { ShizukuState.openDeveloperOptions(ctx) }
                        Chip(backdrop, "Open Shizuku", true) { ShizukuState.openShizuku(ctx) }
                        Chip(backdrop, "Re-check", false) { ShizukuState.refresh() }
                    }
                }
                ShizukuStatus.NeedsPermission -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(backdrop, "Grant access", true) { ShizukuState.requestPermission() }
                    Chip(backdrop, "Open Shizuku", false) { ShizukuState.openShizuku(ctx) }
                }
                ShizukuStatus.Ready -> Chip(backdrop, "Re-check", false) { ShizukuState.refresh() }
            }
            Label("When Claude wants to act", bold = true, size = 15)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Ask every time", "Auto-approve safe", "Auto (never ask)").forEachIndexed { i, l ->
                    Chip(backdrop, l, agentPrefs.mode == i) { Repo.setAgent(Repo.agent.value.copy(mode = i)) }
                }
            }
            Label(when (agentPrefs.mode) {
                0 -> "Every action asks first. Safest."
                1 -> "Reading files, listing apps and similar run on their own; anything that changes the phone asks."
                else -> "Everything runs without asking. Catastrophic commands stay blocked, but prompt injection from web pages or files could cause real damage — use with care."
            }, color = P.sub, size = 12)
            Label("Max steps per request", bold = true, size = 15)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 20, 40).forEach { n -> Chip(backdrop, "$n", agentPrefs.maxSteps == n) { Repo.setAgent(Repo.agent.value.copy(maxSteps = n)) } }
            }
            Label("Folders Claude may change without a high-risk warning", bold = true, size = 15)
            agentPrefs.roots.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Label(r, Modifier.weight(1f), size = 14, font = FontFamily.Monospace)
                    Label("✕", Modifier.tap { Repo.setAgent(Repo.agent.value.copy(roots = Repo.agent.value.roots - r)) }.padding(8.dp), P.sub, 15, true)
                }
            }
            GlassField(backdrop, newRoot, { newRoot = it }, "/sdcard/Documents", Modifier.fillMaxWidth(), singleLine = true)
            Chip(backdrop, "Add folder", false) {
                val r = newRoot.trim().trimEnd('/')
                if (r.startsWith("/") && r.length > 1) { Repo.setAgent(Repo.agent.value.copy(roots = (Repo.agent.value.roots + r).distinct())); newRoot = "" }
                else Toast.makeText(ctx, "Enter an absolute path like /sdcard/Documents", Toast.LENGTH_SHORT).show()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Use limited local shell if Shizuku is off", Modifier.weight(1f))
                Switch(agentPrefs.localFallback) { Repo.setAgent(Repo.agent.value.copy(localFallback = it)) }
            }
            Chip(backdrop, "Activity log & undo", false, onActivity)
        }
        Section("Appearance & liquid glass") {
            val ui by Repo.ui.collectAsState()
            fun upd(f: (UiPrefs) -> UiPrefs) = Repo.setUi(f(Repo.ui.value))
            Label("Theme", bold = true, size = 15)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("System", "Light", "Dark").forEachIndexed { i, l -> Chip(backdrop, l, ui.themeMode == i) { upd { it.copy(themeMode = i) } } }
            }
            Label("Accent colour", bold = true, size = 15)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0xFFD97757, 0xFFC6613F, 0xFF6C8EF5, 0xFF5FA67A, 0xFF9B7BE8, 0xFFE27396, 0xFFD4A27F, 0xFF3B3B3B).forEach { c ->
                    val col = Color(c.toInt())
                    Box(Modifier.size(36.dp).background(col, Capsule()).let { if (ui.accent == c.toInt()) it.border(3.dp, P.text, Capsule()) else it }.tap { upd { it.copy(accent = c.toInt()) } })
                }
            }
            var hex by remember(ui.accent) { mutableStateOf("%06X".format(ui.accent and 0xFFFFFF)) }
            GlassField(backdrop, hex, { v ->
                hex = v.uppercase().filter { it.isLetterOrDigit() }.take(6)
                if (hex.length == 6 && hex.all { it in "0123456789ABCDEF" }) upd { it.copy(accent = (0xFF000000L or hex.toLong(16)).toInt()) }
            }, "Custom hex, e.g. D97757", Modifier.fillMaxWidth(), singleLine = true)
            Label("Reply font", bold = true, size = 15)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Serif", "Sans", "Mono").forEachIndexed { i, l -> Chip(backdrop, l, ui.replyFont == i) { upd { it.copy(replyFont = i) } } }
            }
            GlassSlider("Text size", ui.textScale, 0.85f, 1.35f, { "%.0f%%".format(it * 100) }) { v -> upd { it.copy(textScale = v) } }
            GlassSlider("Background glow", ui.aura, 0f, 1.6f, { "%.0f%%".format(it * 100) }) { v -> upd { it.copy(aura = v) } }
            Label("Liquid glass", bold = true, size = 17, font = FontFamily.Serif)
            GlassPreview()
            GlassSlider("Blur", ui.blur, 0f, 24f, { "%.0f dp".format(it) }) { v -> upd { it.copy(blur = v) } }
            GlassSlider("Refraction edge", ui.lensH, 0f, 40f, { "%.0f dp".format(it) }) { v -> upd { it.copy(lensH = v) } }
            GlassSlider("Refraction strength", ui.lensAmt, 0f, 60f, { "%.0f dp".format(it) }) { v -> upd { it.copy(lensAmt = v) } }
            GlassSlider("Glass opacity", ui.tint, 0f, 0.8f, { "%.0f%%".format(it * 100) }) { v -> upd { it.copy(tint = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Vibrancy (richer colours through glass)", Modifier.weight(1f))
                Switch(ui.vibrancy) { v -> upd { it.copy(vibrancy = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(backdrop, "Reset glass", false) { upd { it.copy(blur = 8f, lensH = 24f, lensAmt = 24f, tint = 0.4f, vibrancy = true) } }
                Chip(backdrop, "Reset all looks", false) { Repo.setUi(UiPrefs()) }
            }
        }
        Section("Model") {
            Label("${Repo.activeProvider().name} · ${com.example.claude.data.shortModel(model)}", bold = true)
            Label("Tap the model name at the top of a chat to switch provider or model.", color = P.sub, size = 13)
        }
        Section("Response style") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Styles.all.keys.take(2).forEach { k -> Chip(backdrop, k, style == k) { Repo.setStyle(k) } } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Styles.all.keys.drop(2).forEach { k -> Chip(backdrop, k, style == k) { Repo.setStyle(k) } } }
        }
        Section("Custom instructions") {
            Label("Tell Claude about yourself or how you'd like it to respond in every chat.", color = P.sub, size = 13)
            GlassField(backdrop, instr, { instr = it; Repo.setInstructions(it) }, "e.g. I'm a nurse. Keep explanations practical.", Modifier.fillMaxWidth(), maxLines = 6)
        }
        Section("Memory") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Use memory in chats", Modifier.weight(1f))
                Switch(memoryOn) { Repo.setMemoryOn(it) }
            }
            Label("Tap “Remember” under any of your messages to save it here.", color = P.sub, size = 13)
            memories.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Label("• $m", Modifier.weight(1f), size = 14)
                    Label("✕", Modifier.tap { Repo.removeMemory(m) }.padding(8.dp), P.sub, 15, true)
                }
            }
            GlassField(backdrop, newMemory, { newMemory = it }, "Add something to remember", Modifier.fillMaxWidth(), singleLine = true)
            Chip(backdrop, "Add memory", false) { Repo.addMemory(newMemory); newMemory = "" }
        }
        Section("Web search") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("On by default for new chats", Modifier.weight(1f))
                Switch(webDefault) { Repo.setWebDefault(it) }
            }
        }
        Section("Sync across devices") {
            if (syncUri == null) {
                Label("Keeps chats, projects, memory and schedules in one file. Save it in Google Drive, Nextcloud, Dropbox or Syncthing and every device that opens the same file stays in sync. Your API key and attachments are never written to it.", color = P.sub, size = 13)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(backdrop, "Create sync file", true) { createSync.launch("claude-sync.json") }
                    Chip(backdrop, "Use existing file", false) { openSync.launch(arrayOf("application/json", "*/*")) }
                }
            } else {
                Label("✅ Sync is on · " + if (lastSync == 0L) "not synced yet" else "last " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(lastSync)), size = 14)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(backdrop, "Sync now", true) { Sync.syncSoon(true) }
                    Chip(backdrop, "Turn off", false) { Repo.setSyncUri(null) }
                }
                Label("Chats deleted on one device aren't removed from the others.", color = P.sub, size = 12)
            }
        }
        Section("Scheduled prompts") {
            Label("Needs notification and exact-alarm permission to deliver on time.", color = P.sub, size = 13)
            GlassButton(backdrop, needPerms) { Label("Check permissions", bold = true) }
        }
        Section("Data") {
            GlassButton(backdrop, { Repo.clearChats(); Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show() }) { Label("Clear chat history", bold = true) }
        }
        Label("Clause · unofficial client built with Jetpack Compose & Kyant0's AndroidLiquidGlass", color = P.sub, size = 12)
    }
}

@Composable
private fun GlassSlider(label: String, value: Float, min: Float, max: Float, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    val bd = LocalBackdrop.current
    Column(Modifier.fillMaxWidth()) {
        Row { Label(label, Modifier.weight(1f), size = 14); Label(fmt(value), color = P.sub, size = 13) }
        if (bd != null) LiquidSlider(value = { value }, onValueChange = onChange, valueRange = min..max,
            visibilityThreshold = (max - min) / 500f, backdrop = bd, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun GlassPreview() {
    val pb = rememberLayerBackdrop()
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(22.dp))) {
        Box(Modifier.fillMaxSize().layerBackdrop(pb)
            .background(Brush.linearGradient(listOf(P.accent, Color(0xFF6C8EF5), Color(0xFFE27396))))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.4f), 38.dp.toPx(), Offset(size.width * 0.25f, size.height * 0.5f))
                drawRect(Color.Black.copy(alpha = 0.28f), Offset(size.width * 0.58f, 18.dp.toPx()), Size(size.width * 0.28f, size.height - 36.dp.toPx()))
            })
        Row(Modifier.align(Alignment.Center).glass(pb, Capsule()).padding(horizontal = 28.dp, vertical = 16.dp)) { Label("Liquid glass", bold = true, size = 17) }
    }
}

/** Shown before Clause acts on the phone in "Ask" modes. Deny is the default (tap outside / back). */
@Composable
fun ApprovalSheet(req: ApprovalRequest, backdrop: Backdrop, onDecide: (Decision) -> Unit) {
    BackHandler { onDecide(Decision.DENY) }
    Sheet({ onDecide(Decision.DENY) }) {
        Label("Clause wants to act on your phone", bold = true, size = 19, font = FontFamily.Serif)
        Label(when (req.risk) { Risk.SAFE -> "Low risk · read-only"; Risk.MODERATE -> "Changes something"; Risk.DANGEROUS -> "High risk" },
            color = when (req.risk) { Risk.SAFE -> Color(0xFF3E9B63); Risk.MODERATE -> Color(0xFFD89A2B); Risk.DANGEROUS -> Color(0xFFD64545) }, bold = true, size = 13)
        Label(req.summary, bold = true, size = 15)
        BasicText(req.detail.take(700), Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(12.dp)).padding(10.dp),
            TextStyle(P.sub, 12.sp, fontFamily = FontFamily.Monospace))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(backdrop, { onDecide(Decision.DENY) }, Modifier.weight(1f)) { Label("Deny", bold = true) }
            GlassButton(backdrop, { onDecide(Decision.ONCE) }, Modifier.weight(1f), tint = P.accent.copy(alpha = 0.92f)) { Label("Allow once", color = Color.White, bold = true) }
        }
        if (req.canSession) Chip(backdrop, "Allow “${req.tool}” for this chat", false) { onDecide(Decision.SESSION) }
    }
}
