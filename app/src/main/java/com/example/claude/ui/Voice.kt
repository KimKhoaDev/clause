package com.example.claude.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Speech-to-text using the platform recogniser. Must be used on the main thread. */
class VoiceInput(
    private val ctx: Context,
    private val onText: (text: String, final: Boolean) -> Unit,
    private val onState: (listening: Boolean) -> Unit,
) {
    private var rec: SpeechRecognizer? = null

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Toast.makeText(ctx, "Speech recognition isn't available on this device", Toast.LENGTH_LONG).show(); return
        }
        destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(ctx).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { onState(false) }
                override fun onResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onText(it, true) }
                    onState(false)
                }
                override fun onPartialResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onText(it, false) }
                }
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            })
        }
        onState(true)
    }

    fun stop() { rec?.stopListening() }
    fun destroy() { rec?.destroy(); rec = null }
}

/** Text-to-speech ("Listen" on a reply). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null
    private var doneCb: (() -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    var speakingKey by mutableStateOf<String?>(null); private set

    private fun clean(t: String) = t.replace(Regex("```[\\s\\S]*?```"), " code block. ")
        .replace(Regex("[*_#`>]"), "").replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")

    fun toggle(ctx: Context, key: String, text: String) {
        if (speakingKey == key) { stop(); return }
        if (tts == null) {
            tts = TextToSpeech(ctx.applicationContext) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { if (speakingKey == id) { speakingKey = null; val cb = doneCb; doneCb = null; if (cb != null) main.post(cb) } }
                        @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (speakingKey == id) speakingKey = null }
                    })
                    pending?.let { speak(it.first, it.second) }; pending = null
                }
            }
        }
        if (ready) speak(key, text) else pending = key to text
    }

    private fun speak(key: String, text: String) {
        speakingKey = key
        val body = clean(text)
        // TTS has an input length limit, so split into chunks
        body.chunked(3500).forEachIndexed { i, part ->
            val last = i == body.chunked(3500).lastIndex
            tts?.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (last) key else "$key#$i")
        }
    }

    fun speakThen(ctx: Context, key: String, text: String, onDone: () -> Unit) {
        stop(); doneCb = onDone; toggle(ctx, key, text)
    }

    fun stop() { doneCb = null; tts?.stop(); speakingKey = null }
}
