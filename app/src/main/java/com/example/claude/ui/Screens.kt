package com.example.claude.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.claude.ui.glass.LiquidBottomTab
import com.example.claude.ui.glass.LiquidBottomTabs
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.claude.data.Chat
import com.example.claude.data.Models
import com.example.claude.data.Repeat
import com.example.claude.data.Repo
import com.example.claude.data.Schedule
import com.example.claude.data.Styles
import com.example.claude.net.ClaudeApi
import com.example.claude.work.Scheduler
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class Screen { Chat, History, Projects, Schedules, Settings, Activity }

// ------------------------------------------------------------------ shell

private fun titleOf(s: Screen) = when (s) {
    Screen.History -> "Chats"; Screen.Projects -> "Projects"; Screen.Schedules -> "Scheduled"; Screen.Settings -> "Settings"; Screen.Activity -> "Activity"; else -> ""
}

@Composable
fun App(ctl: ChatController, pendingChat: String?, onPendingHandled: () -> Unit, needPerms: () -> Unit) {
    val key by Repo.apiKey.collectAsState()
    var screen by remember { mutableStateOf(Screen.Chat) }
    val backdrop = rememberLayerBackdrop()

    LaunchedEffect(pendingChat) {
        if (pendingChat != null) { ctl.open(pendingChat); screen = Screen.Chat; onPendingHandled() }
    }
    BackHandler(enabled = key != null && screen != Screen.Chat) { screen = Screen.Chat }

    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().background(P.bg)) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { Aura() }
            if (key == null) LoginScreen(backdrop)
            else if (screen == Screen.Chat) ChatScreen(ctl, backdrop, onMenu = { screen = Screen.History })
            else TabScaffold(titleOf(screen), if (screen == Screen.Activity) Screen.Settings else screen, backdrop, { screen = it }) { pad ->
                when (screen) {
                    Screen.History -> HistoryScreen(ctl, backdrop, pad) { screen = Screen.Chat }
                    Screen.Projects -> ProjectsScreen(ctl, backdrop, pad) { screen = Screen.Chat }
                    Screen.Schedules -> SchedulesScreen(backdrop, pad, needPerms)
                    Screen.Settings -> SettingsScreen(backdrop, pad, needPerms, onActivity = { screen = Screen.Activity }) { screen = Screen.Chat; ctl.newChat() }
                    Screen.Activity -> ActivityScreen(backdrop, pad)
                    else -> {}
                }
            }
        }
    }
}

/** Soft colour blobs so the liquid glass has something to refract. */
@Composable
fun Aura() {
    val a = P.accent
    val k = LocalUi.current.aura
    Box(Modifier.fillMaxSize().drawBehind {
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.35f * k), Color.Transparent), Offset(size.width * 0.9f, size.height * 0.12f), size.width * 0.7f),
            size.width * 0.7f, Offset(size.width * 0.9f, size.height * 0.12f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF7C9EE8).copy(alpha = 0.22f * k), Color.Transparent), Offset(size.width * 0.05f, size.height * 0.55f), size.width * 0.6f),
            size.width * 0.6f, Offset(size.width * 0.05f, size.height * 0.55f))
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.22f * k), Color.Transparent), Offset(size.width * 0.7f, size.height * 0.95f), size.width * 0.65f),
            size.width * 0.65f, Offset(size.width * 0.7f, size.height * 0.95f))
    })
}

@Composable
fun insetTop(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
@Composable
fun insetBottom(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Page content sits in its own backdrop layer so the floating header and Liquid tab bar refract it as it scrolls. */
@Composable
fun TabScaffold(title: String, current: Screen, backdrop: Backdrop, go: (Screen) -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val page = rememberLayerBackdrop()
    val tabs = listOf(Screen.History to ("💬" to "Chats"), Screen.Projects to ("📁" to "Projects"), Screen.Schedules to ("⏰" to "Scheduled"), Screen.Settings to ("⚙" to "Settings"))
    val sel = tabs.indexOfFirst { it.first == current }.coerceAtLeast(0)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(page)) {
            Aura()
            content(PaddingValues(top = insetTop() + 72.dp, bottom = insetBottom() + 110.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = insetTop() + 10.dp, start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIcon(page, "‹", { go(Screen.Chat) })
            Spacer(Modifier.width(12.dp))
            Label(title, size = 26, font = androidx.compose.ui.text.font.FontFamily.Serif)
        }
        LiquidBottomTabs(
            selectedTabIndex = { sel }, onTabSelected = { go(tabs[it].first) }, backdrop = page, tabsCount = tabs.size,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).padding(bottom = insetBottom() + 14.dp),
        ) {
            tabs.forEach { (s, il) ->
                LiquidBottomTab({ go(s) }) { Label(il.first, size = 20); Label(il.second, size = 10, bold = true) }
            }
        }
    }
}

@Composable
fun GlassField(
    backdrop: Backdrop, value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    singleLine: Boolean = false, password: Boolean = false, maxLines: Int = 5, keyboard: KeyboardType = KeyboardType.Text,
) {
    Box(modifier.glass(backdrop, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Label(hint, color = P.sub)
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth(),
            textStyle = TextStyle(P.text, 16.sp), cursorBrush = SolidColor(P.accent),
            singleLine = singleLine, maxLines = if (singleLine) 1 else maxLines,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        )
    }
}

// ------------------------------------------------------------------ login

@Composable
fun LoginScreen(backdrop: Backdrop) {
    var pid by remember { mutableStateOf("anthropic") }
    val p = Repo.providerById(pid)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = insetTop(), bottom = insetBottom()).imePadding(),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).background(P.accent, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
            Label("✺", color = Color.White, size = 38, bold = true)
        }
        Spacer(Modifier.height(18.dp))
        Label("Clause", size = 40, font = androidx.compose.ui.text.font.FontFamily.Serif)
        Spacer(Modifier.height(6.dp))
        Label("Pick a provider and add your API key", color = P.sub)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Repo.allProviders().forEach { pr -> Chip(backdrop, pr.name, pr.id == pid) { pid = pr.id } }
        }
        Spacer(Modifier.height(16.dp))
        ProviderKeyForm(backdrop, p) { }
        Spacer(Modifier.height(16.dp))
        Label("For Google models use the Gemini provider with a free key from aistudio.google.com. Keys stay encrypted on this device and are only sent to the provider you choose.", color = P.sub, size = 12)
    }
}

// ------------------------------------------------------------------ schedules

private data class Template(val name: String, val prompt: String, val h: Int, val m: Int, val ws: Boolean = false)
private val templates = listOf(
    Template("Morning briefing", "Give me an energising morning briefing: a motivating thought, three priorities to consider for today, and one quick healthy habit.", 8, 0),
    Template("Word of the day", "Teach me one interesting, uncommon English word with its meaning, origin and two example sentences.", 9, 30),
    Template("Daily journal prompt", "Give me one thoughtful journaling prompt to reflect on my day.", 21, 0),
    Template("Daily news", "Search the web and give me a brief digest of today's most important news in tech, world and science, with one line each.", 7, 30, true),
)

@Composable
fun SchedulesScreen(backdrop: Backdrop, pad: PaddingValues, needPerms: () -> Unit) {
    val ctx = LocalContext.current
    val schedules by Repo.schedules.collectAsState()
    var editing by remember { mutableStateOf<Schedule?>(null) }
    val fmt = remember { SimpleDateFormat("EEE h:mm a", Locale.getDefault()) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, pad.calculateTopPadding(), 16.dp, pad.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassButton(backdrop, {
                    needPerms()
                    editing = Schedule(UUID.randomUUID().toString(), "", "", 8, 0, Repeat.DAILY)
                }, Modifier.fillMaxWidth(), tint = P.accent.copy(alpha = 0.9f)) { Label("＋  New scheduled prompt", color = Color.White, bold = true) }
            }
            if (schedules.isEmpty()) item { Label("Claude can send a prompt for you at a set time and notify you with the reply.", color = P.sub) }
            items(schedules, key = { it.id }) { s ->
                Column(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Label(s.name, bold = true, size = 17)
                            Label("%02d:%02d · %s%s".format(s.hour, s.minute, s.repeat.label, if (s.webSearch) " · 🌐" else ""), color = P.accent, size = 14, bold = true)
                        }
                        Switch(s.enabled) { on ->
                            val n = s.copy(enabled = on); Repo.upsertSchedule(n); Scheduler.schedule(ctx, n); if (on) needPerms()
                        }
                    }
                    Label(s.prompt.take(120), color = P.sub, size = 13)
                    if (s.enabled) Label("Next: " + fmt.format(Date(Scheduler.nextTrigger(s))), color = P.sub, size = 12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(backdrop, "Run now", false) { Scheduler.runNow(ctx, s.id); Toast.makeText(ctx, "Sending…", Toast.LENGTH_SHORT).show() }
                        Chip(backdrop, "Edit", false) { editing = s }
                        Chip(backdrop, "Delete", false) { Scheduler.cancel(ctx, s.id); Repo.deleteSchedule(s.id) }
                    }
                }
            }
        }
        editing?.let { s -> ScheduleEditor(backdrop, s, { editing = null }) { saved ->
            Repo.upsertSchedule(saved); Scheduler.schedule(ctx, saved); editing = null
        } }
    }
}

@Composable
private fun ScheduleEditor(backdrop: Backdrop, initial: Schedule, onClose: () -> Unit, onSave: (Schedule) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).tap(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth().background(P.bg, RoundedCornerShape(28.dp)).tap { }
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label(if (initial.name.isEmpty()) "New scheduled prompt" else "Edit prompt", size = 20, bold = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                templates.take(2).forEach { t -> Chip(backdrop, t.name, false) { s = s.copy(name = t.name, prompt = t.prompt, hour = t.h, minute = t.m, webSearch = t.ws) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                templates.drop(2).forEach { t -> Chip(backdrop, t.name, false) { s = s.copy(name = t.name, prompt = t.prompt, hour = t.h, minute = t.m, webSearch = t.ws) } }
            }
            GlassField(backdrop, s.name, { s = s.copy(name = it) }, "Name", Modifier.fillMaxWidth(), singleLine = true)
            GlassField(backdrop, s.prompt, { s = s.copy(prompt = it) }, "Prompt to send", Modifier.fillMaxWidth(), maxLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Stepper("%02d".format(s.hour), { s = s.copy(hour = (s.hour + 1) % 24) }, { s = s.copy(hour = (s.hour + 23) % 24) })
                Label(" : ", size = 30, bold = true)
                Stepper("%02d".format(s.minute), { s = s.copy(minute = (s.minute + 5) % 60) }, { s = s.copy(minute = (s.minute + 55) % 60) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Repeat.entries.forEach { r -> Chip(backdrop, r.label, s.repeat == r) { s = s.copy(repeat = r) } }
            }
            Chip(backdrop, "🌐 Use web search", s.webSearch) { s = s.copy(webSearch = !s.webSearch) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(backdrop, onClose, Modifier.weight(1f)) { Label("Cancel", bold = true) }
                GlassButton(backdrop, { onSave(s.copy(enabled = true)) }, Modifier.weight(1f), tint = P.accent.copy(alpha = 0.92f), enabled = s.name.isNotBlank() && s.prompt.isNotBlank()) {
                    Label("Save", color = Color.White, bold = true)
                }
            }
        }
    }
}

@Composable
private fun Stepper(value: String, up: () -> Unit, down: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Label("▲", color = P.accent, size = 20, modifier = Modifier.tap(onClick = up).padding(10.dp))
        Label(value, size = 38, bold = true)
        Label("▼", color = P.accent, size = 20, modifier = Modifier.tap(onClick = down).padding(10.dp))
    }
}

