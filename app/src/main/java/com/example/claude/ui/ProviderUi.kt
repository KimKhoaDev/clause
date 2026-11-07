package com.example.claude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.claude.data.ApiKind
import com.example.claude.data.Models
import com.example.claude.data.Provider
import com.example.claude.data.Providers
import com.example.claude.data.Repo
import com.example.claude.data.shortModel
import com.example.claude.net.AI
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch
import java.util.UUID

/** Add or update the key (and base URL) for one provider. "Enter without verifying" skips the test call. */
@Composable
fun ProviderKeyForm(backdrop: Backdrop, p: Provider, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember(p.id) { mutableStateOf("") }
    var base by remember(p.id) { mutableStateOf(Repo.baseUrlOf(p)) }
    var busy by remember(p.id) { mutableStateOf(false) }
    var err by remember(p.id) { mutableStateOf<String?>(null) }
    fun save() {
        Repo.setBaseUrl(p.id, base); Repo.setKey(p.id, key.trim().ifBlank { "none" }); Repo.setActiveProvider(p.id); onDone()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.note.isNotBlank()) Label(p.note, color = P.sub, size = 13)
        if (p.kind == ApiKind.OPENAI) GlassField(backdrop, base, { base = it.trim(); err = null }, "Base URL", Modifier.fillMaxWidth(), singleLine = true, keyboard = KeyboardType.Uri)
        GlassField(backdrop, key, { key = it.trim(); err = null }, p.keyHint + if (p.keyOptional) " (optional)" else "", Modifier.fillMaxWidth(), singleLine = true, password = true)
        err?.let { Label(it, color = Color(0xFFD64545), size = 13) }
        GlassButton(backdrop, {
            busy = true; err = null
            scope.launch {
                try { Repo.setBaseUrl(p.id, base); AI.validate(p, key.trim().ifBlank { "none" }, Repo.modelOf(p.id)); save() }
                catch (e: Exception) { err = e.message ?: "Couldn't verify the key" }
                busy = false
            }
        }, Modifier.fillMaxWidth(), tint = P.accent.copy(alpha = 0.9f), enabled = !busy && (key.length > 8 || p.keyOptional)) {
            Label(if (busy) "Verifying…" else "Verify & save", color = Color.White, bold = true)
        }
        Chip(backdrop, "Enter without verifying", false) { save() }
    }
}

/** Header pill target: switch provider, browse/search that provider's models, or type any model id. */
@Composable
fun ModelPickerSheet(backdrop: Backdrop, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val activeId by Repo.provider.collectAsState()
    val model by Repo.model.collectAsState()
    val customs by Repo.customs.collectAsState()
    var adding by remember { mutableStateOf<Provider?>(null) }
    var models by remember(activeId) { mutableStateOf(listOf<String>()) }
    var loading by remember(activeId) { mutableStateOf(false) }
    var err by remember(activeId) { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    val prov = Repo.providerById(activeId)
    fun load() {
        scope.launch {
            loading = true; err = null
            try { models = AI.fetchModels(prov, Repo.apiKey.value.orEmpty()) } catch (e: Exception) { err = e.message }
            loading = false
        }
    }
    LaunchedEffect(activeId) {
        if (prov.kind == ApiKind.ANTHROPIC) models = Models.all.map { it.id } else if (Repo.keyOf(activeId) != null) load()
    }
    Sheet(onClose) {
        Label("Provider & model", bold = true, size = 19, font = FontFamily.Serif)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (Providers.builtins + customs).forEach { pr ->
                val ok = Repo.keyOf(pr.id) != null
                Chip(backdrop, (if (ok) "" else "🔒 ") + pr.name, pr.id == activeId && adding == null) {
                    if (ok) { adding = null; Repo.setActiveProvider(pr.id) } else adding = pr
                }
            }
        }
        val a = adding
        if (a != null) ProviderKeyForm(backdrop, a) { adding = null }
        else {
            Label("Using ${shortModel(model)}", color = P.sub, size = 13)
            if (prov.kind == ApiKind.OPENAI) {
                Chip(backdrop, if (loading) "Loading models…" else "↻ Fetch models", false) { load() }
                GlassField(backdrop, q, { q = it }, "Search models", Modifier.fillMaxWidth(), singleLine = true)
            }
            err?.let { Label(it, color = Color(0xFFD64545), size = 12) }
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                models.filter { q.isBlank() || it.contains(q, true) }.take(80).forEach { id ->
                    Label((if (id == model) "● " else "   ") + id, Modifier.fillMaxWidth().tap { Repo.setModel(id); onClose() }.padding(vertical = 9.dp),
                        if (id == model) P.accent else P.text, 14, id == model)
                }
            }
            GlassField(backdrop, manual, { manual = it.trim() }, "Or type any model id", Modifier.fillMaxWidth(), singleLine = true)
            Chip(backdrop, "Use this id", false) { if (manual.isNotBlank()) { Repo.setModel(manual); onClose() } }
        }
    }
}

/** Settings → AI providers. */
@Composable
fun ProvidersSection(backdrop: Backdrop) {
    val activeId by Repo.provider.collectAsState()
    val customs by Repo.customs.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf(false) }
    Label("Choose who Clause talks to. Keys are stored encrypted on this device.", color = P.sub, size = 13)
    (Providers.builtins + customs).forEach { pr ->
        val has = Repo.keyOf(pr.id) != null
        Column(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(pr.name + if (pr.id == activeId && has) "  · active" else "", bold = true)
                    Label(if (has) "Key saved · ${shortModel(Repo.modelOf(pr.id))}" else pr.note.ifBlank { "Not set up" }, color = P.sub, size = 12)
                }
                if (has && pr.id != activeId) Chip(backdrop, "Use", true) { Repo.setActiveProvider(pr.id) }
                Chip(backdrop, if (has) "Edit" else "Add", false) { editing = if (editing == pr.id) null else pr.id }
            }
            if (editing == pr.id) {
                ProviderKeyForm(backdrop, pr) { editing = null }
                if (has) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(backdrop, "Remove key", false) { Repo.setKey(pr.id, null); editing = null }
                    if (!pr.builtin) Chip(backdrop, "Delete provider", false) { Repo.removeCustom(pr.id); editing = null }
                }
            }
        }
    }
    Chip(backdrop, if (custom) "Cancel" else "＋ Custom OpenAI-compatible", false) { custom = !custom }
    if (custom) CustomProviderForm(backdrop) { custom = false }
}

@Composable
private fun CustomProviderForm(backdrop: Backdrop, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassField(backdrop, name, { name = it }, "Name", Modifier.fillMaxWidth(), singleLine = true)
        GlassField(backdrop, base, { base = it.trim() }, "Base URL, e.g. https://host/v1", Modifier.fillMaxWidth(), singleLine = true, keyboard = KeyboardType.Uri)
        GlassField(backdrop, model, { model = it.trim() }, "Model id", Modifier.fillMaxWidth(), singleLine = true)
        GlassField(backdrop, key, { key = it.trim() }, "API key (optional)", Modifier.fillMaxWidth(), singleLine = true, password = true)
        GlassButton(backdrop, {
            val p = Provider("custom_" + UUID.randomUUID().toString().take(8), name.trim(), ApiKind.OPENAI, base.trimEnd('/'), model, "API key",
                vision = true, builtin = false, keyOptional = true, note = "Custom OpenAI-compatible endpoint")
            Repo.addCustom(p); Repo.setKey(p.id, key.ifBlank { "none" }); Repo.setActiveProvider(p.id); onDone()
        }, Modifier.fillMaxWidth(), tint = P.accent.copy(alpha = 0.9f), enabled = name.isNotBlank() && base.startsWith("http") && model.isNotBlank()) {
            Label("Add provider", color = Color.White, bold = true)
        }
    }
}
