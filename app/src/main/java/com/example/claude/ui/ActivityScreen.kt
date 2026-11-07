package com.example.claude.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.claude.agent.AgentStore
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What Claude changed on the phone (with Undo) and a log of every tool call. Idea: Mobile-Harness per-file Undo/Keep. */
@Composable
fun ActivityScreen(backdrop: Backdrop, pad: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by AgentStore.changes.collectAsState()
    val log by AgentStore.log.collectAsState()
    val fmt = remember { SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()) }
    val pending = changes.filter { it.status == "pending" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, pad.calculateTopPadding(), 16.dp, pad.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Label("File changes", bold = true, size = 20, font = FontFamily.Serif)
            Label("Undo puts a file back exactly as it was before Claude touched it.", color = P.sub, size = 13)
            if (pending.isNotEmpty()) Row(Modifier.padding(top = 8.dp)) {
                Chip(backdrop, "Undo all ${pending.size} pending", true) {
                    scope.launch { pending.forEach { AgentStore.undo(it.id) }; Toast.makeText(ctx, "Reverted", Toast.LENGTH_SHORT).show() }
                }
            }
        }
        if (changes.isEmpty()) item { Label("Nothing yet.", color = P.sub) }
        items(changes.take(100), key = { it.id }) { c ->
            Column(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(20.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(c.path, size = 13, font = FontFamily.Monospace)
                Label("${c.tool} · ${c.note} · ${c.status} · ${fmt.format(Date(c.time))}", color = P.sub, size = 12)
                if (c.status == "pending") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (c.backup != null || !c.existed) Chip(backdrop, "Undo", false) {
                        scope.launch { Toast.makeText(ctx, AgentStore.undo(c.id), Toast.LENGTH_SHORT).show() }
                    }
                    Chip(backdrop, "Keep", false) { AgentStore.keep(c.id) }
                }
            }
        }
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Label("Activity log", Modifier.weight(1f), bold = true, size = 20, font = FontFamily.Serif)
                Chip(backdrop, "Clear", false) { AgentStore.clearLog() }
            }
        }
        if (log.isEmpty()) item { Label("No device actions yet.", color = P.sub) }
        items(log.take(150)) { l ->
            val icon = when (l.decision) { "blocked" -> "⛔"; "denied" -> "🚫"; else -> if (l.ok) "✓" else "✗" }
            Column(Modifier.fillMaxWidth().background(P.card, RoundedCornerShape(20.dp)).padding(12.dp)) {
                Label("$icon  ${l.summary}", size = 13, font = FontFamily.Monospace)
                Label("${l.risk.lowercase()} · ${l.decision} · ${fmt.format(Date(l.time))}", color = P.sub, size = 11)
            }
        }
    }
}




