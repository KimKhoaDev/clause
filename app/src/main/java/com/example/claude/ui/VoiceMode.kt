package com.example.claude.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val VOICE_PROMPT = "This is a spoken conversation. Reply in one to three short, natural sentences. Plain text only: no markdown, lists, code or emoji."

private enum class Phase(val title: String) { Listening("Listening…"), Thinking("Thinking…"), Speaking("Speaking — tap to interrupt") }

/** Hands-free loop: listen -> send -> speak the reply -> listen again, until the user ends it. */
@Composable
fun VoiceModeOverlay(ctl: ChatController, backdrop: Backdrop, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.Listening) }
    var heard by remember { mutableStateOf("") }
    var said by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(true) }
    var handled by remember { mutableStateOf(false) }
    val holder = remember { arrayOfNulls<VoiceInput>(1) }

    fun listen() {
        if (!active) return
        handled = false; phase = Phase.Listening; heard = ""
        holder[0]?.start()
    }
    fun submit(t: String) {
        handled = true; phase = Phase.Thinking; heard = t
        ctl.send(t, VOICE_PROMPT) { reply ->
            if (!active) return@send
            if (reply == null) scope.launch { delay(1200); listen() }
            else { said = reply; phase = Phase.Speaking; Speaker.speakThen(ctx, "voice", reply) { listen() } }
        }
    }
    remember {
        holder[0] = VoiceInput(ctx,
            { t, final -> heard = t; if (final && !handled && t.isNotBlank()) submit(t) },
            { listening ->
                if (!listening && !handled && phase == Phase.Listening && active) scope.launch {
                    delay(700); if (active && !handled && phase == Phase.Listening) listen()
                }
            })
        true
    }
    LaunchedEffect(Unit) { listen() }
    DisposableEffect(Unit) { onDispose { active = false; holder[0]?.destroy(); Speaker.stop(); ctl.stop() } }
    BackHandler(onBack = onClose)

    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(
        1f, if (phase == Phase.Listening) 1.14f else if (phase == Phase.Speaking) 1.08f else 1.03f,
        infiniteRepeatable(tween(if (phase == Phase.Thinking) 1400 else 800), RepeatMode.Reverse), label = "scale",
    )

    Column(
        Modifier.fillMaxSize().background(P.bg.copy(alpha = 0.94f)).padding(top = insetTop() + 24.dp, bottom = insetBottom() + 24.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Label("Voice conversation", bold = true, size = 18)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(190.dp).scale(pulse).glass(backdrop, Capsule(), P.accent.copy(alpha = if (phase == Phase.Thinking) 0.45f else 0.8f))
                    .tap {
                        when (phase) {
                            Phase.Speaking -> { Speaker.stop(); listen() }
                            Phase.Listening -> holder[0]?.stop()
                            else -> {}
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Label("✺", color = Color.White, size = 64, bold = true) }
            Spacer(Modifier.height(28.dp))
            Label(phase.title, size = 20, bold = true)
            Spacer(Modifier.height(10.dp))
            Label(if (phase == Phase.Speaking) said.take(220) else heard, color = P.sub, size = 15)
        }
        GlassButton(backdrop, onClose, Modifier.fillMaxWidth(), tint = Color(0xFFD64545).copy(alpha = 0.85f)) {
            Label("End conversation", color = Color.White, bold = true)
        }
    }
}
