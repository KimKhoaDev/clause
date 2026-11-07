package com.example.claude.net

import android.util.Base64
import com.example.claude.data.Attachment
import com.example.claude.data.Msg
import com.example.claude.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String, val code: Int = 0, val retryAfterSec: Long? = null, val type: String = "") : Exception(message) {
    /** "credit", "rate", "overloaded" or null when this isn't a usage/availability limit. */
    val limitKind: String?
        get() = when {
            message.orEmpty().contains("credit balance", true) || message.orEmpty().contains("usage limit", true) ||
                message.orEmpty().contains("insufficient", true) || message.orEmpty().contains("more credits", true) || code == 402 -> "credit"
            code == 429 || type == "rate_limit_error" -> "rate"
            code == 529 || type == "overloaded_error" -> "overloaded"
            else -> null
        }
    val isLimit get() = limitKind != null
}

object ClaudeApi {
    private const val URL_MESSAGES = "https://api.anthropic.com/v1/messages"

    private fun open(key: String): HttpURLConnection =
        (URL(URL_MESSAGES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("content-type", "application/json")
        }

    private fun isText(a: Attachment) = !a.mime.startsWith("image/") && a.mime != "application/pdf"

    private fun content(m: Msg): Any {
        if (m.atts.isEmpty()) return m.text
        val arr = JSONArray()
        m.atts.forEach { a ->
            val f = File(a.path)
            if (!f.exists()) return@forEach
            when {
                a.mime.startsWith("image/") -> arr.put(JSONObject().put("type", "image").put("source",
                    JSONObject().put("type", "base64").put("media_type", a.mime)
                        .put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))))
                a.mime == "application/pdf" -> arr.put(JSONObject().put("type", "document").put("source",
                    JSONObject().put("type", "base64").put("media_type", "application/pdf")
                        .put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))))
                isText(a) -> arr.put(JSONObject().put("type", "text").put("text", "<file name=\"${a.name}\">\n${f.readText()}\n</file>"))
            }
        }
        if (m.text.isNotBlank() || arr.length() == 0) arr.put(JSONObject().put("type", "text").put("text", m.text.ifBlank { "(see attachment)" }))
        return arr
    }

    /** Rebuilds API messages from stored chat, expanding agent turns (tool_use / tool_result) from their saved raw form. */
    fun buildMessages(msgs: List<Msg>): JSONArray = JSONArray().also { a ->
        msgs.forEach { m ->
            val raw = m.raw?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (m.role == "assistant" && raw != null) for (k in 0 until raw.length()) a.put(raw.getJSONObject(k))
            else a.put(JSONObject().put("role", m.role).put("content", content(m)))
        }
    }

    class Turn(val content: JSONArray, val stopReason: String)

    /** One streamed model turn with client-side tools. Returns the assembled content blocks and stop reason. */
    suspend fun streamTurn(
        key: String, model: String, system: String, messages: JSONArray, tools: JSONArray,
        maxTokens: Int = 4096, onText: (String) -> Unit,
    ): Turn = withContext(Dispatchers.IO) {
        val c = open(key)
        try {
            val payload = JSONObject().put("model", model).put("max_tokens", maxTokens).put("stream", true)
                .put("messages", messages).put("tools", tools)
            if (system.isNotBlank()) payload.put("system", system)
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (c.responseCode !in 200..299) throw errorOf(c)
            val blocks = JSONArray(); var cur: JSONObject? = null
            val inputBuf = StringBuilder(); val textBuf = StringBuilder(); var stop = "end_turn"
            val job = currentCoroutineContext()[Job]
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (job?.isActive == false) break
                    if (!line.startsWith("data:")) continue
                    val j = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                    when (j.optString("type")) {
                        "content_block_start" -> { cur = j.optJSONObject("content_block"); inputBuf.clear(); textBuf.clear() }
                        "content_block_delta" -> {
                            val d = j.optJSONObject("delta") ?: continue
                            when (d.optString("type")) {
                                "text_delta" -> { val t = d.getString("text"); textBuf.append(t); onText(t) }
                                "input_json_delta" -> inputBuf.append(d.optString("partial_json"))
                            }
                        }
                        "content_block_stop" -> {
                            val b = cur
                            if (b != null) when (b.optString("type")) {
                                "text" -> if (textBuf.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", textBuf.toString()))
                                "tool_use" -> blocks.put(JSONObject().put("type", "tool_use").put("id", b.getString("id")).put("name", b.getString("name"))
                                    .put("input", runCatching { JSONObject(inputBuf.toString().ifBlank { "{}" }) }.getOrDefault(JSONObject())))
                            }
                            cur = null
                        }
                        "message_delta" -> j.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() && it != "null" }?.let { stop = it }
                        "error" -> throw ApiException(j.optJSONObject("error")?.optString("message") ?: "Stream error", 0, null, j.optJSONObject("error")?.optString("type").orEmpty())
                    }
                }
            }
            Turn(blocks, stop)
        } finally { c.disconnect() }
    }

    private fun body(model: String, system: String, msgs: List<Msg>, stream: Boolean, maxTokens: Int, web: Boolean): String =
        JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", stream)
            if (system.isNotBlank()) put("system", system)
            if (web) put("tools", JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 5)))
            put("messages", buildMessages(msgs))
        }.toString()

    private fun errorOf(c: HttpURLConnection): ApiException {
        val raw = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        val err = runCatching { JSONObject(raw).getJSONObject("error") }.getOrNull()
        val msg = err?.optString("message")?.takeIf { it.isNotBlank() } ?: raw.ifBlank { "HTTP ${c.responseCode}" }
        val retry = c.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.toLong()
        return ApiException(msg, c.responseCode, retry, err?.optString("type").orEmpty())
    }

    private fun sourcesOf(block: JSONObject): List<Source> {
        val c = block.optJSONArray("content") ?: return emptyList()
        return (0 until c.length()).mapNotNull { i ->
            c.optJSONObject(i)?.takeIf { it.optString("type") == "web_search_result" }
                ?.let { Source(it.optString("title"), it.optString("url")) }
        }
    }

    /** Streams text deltas; returns the full text. Supports the web search tool. */
    suspend fun stream(
        key: String, model: String, system: String, msgs: List<Msg>, web: Boolean,
        onDelta: (String) -> Unit, onStatus: (String?) -> Unit, onSources: (List<Source>) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val c = open(key)
        try {
            c.outputStream.use { it.write(body(model, system, msgs, true, 4096, web).toByteArray()) }
            if (c.responseCode !in 200..299) throw errorOf(c)
            val sb = StringBuilder()
            val job = currentCoroutineContext()[Job]
            val toolInput = StringBuilder()
            var inTool = false
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (job?.isActive == false) break
                    if (!line.startsWith("data:")) continue
                    val j = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                    when (j.optString("type")) {
                        "content_block_start" -> {
                            val b = j.optJSONObject("content_block") ?: continue
                            when (b.optString("type")) {
                                "server_tool_use" -> { inTool = true; toolInput.clear(); onStatus("🌐 Searching the web…") }
                                "web_search_tool_result" -> { onSources(sourcesOf(b)); onStatus(null) }
                                "text" -> if (sb.isNotEmpty()) { sb.append("\n\n"); onDelta("\n\n") }
                            }
                        }
                        "content_block_delta" -> {
                            val d = j.optJSONObject("delta") ?: continue
                            when (d.optString("type")) {
                                "text_delta" -> { val t = d.getString("text"); sb.append(t); onDelta(t) }
                                "input_json_delta" -> if (inTool) toolInput.append(d.optString("partial_json"))
                            }
                        }
                        "content_block_stop" -> if (inTool) {
                            inTool = false
                            runCatching { JSONObject(toolInput.toString()).optString("query") }.getOrNull()
                                ?.takeIf { it.isNotBlank() }?.let { onStatus("🌐 Searching: $it") }
                        }
                        "error" -> throw ApiException(j.optJSONObject("error")?.optString("message") ?: "Stream error", 0, null, j.optJSONObject("error")?.optString("type").orEmpty())
                    }
                }
            }
            onStatus(null)
            sb.toString()
        } finally { c.disconnect() }
    }

    /** One-shot completion (scheduled prompts, titles, key validation). */
    suspend fun completeFull(
        key: String, model: String, system: String, msgs: List<Msg>, maxTokens: Int = 2048, web: Boolean = false,
    ): Pair<String, List<Source>> = withContext(Dispatchers.IO) {
        val c = open(key)
        try {
            c.outputStream.use { it.write(body(model, system, msgs, false, maxTokens, web).toByteArray()) }
            if (c.responseCode !in 200..299) throw errorOf(c)
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val arr = j.getJSONArray("content")
            val text = StringBuilder(); val src = mutableListOf<Source>()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> { if (text.isNotEmpty()) text.append("\n\n"); text.append(b.optString("text")) }
                    "web_search_tool_result" -> src += sourcesOf(b)
                }
            }
            text.toString() to src.distinctBy { it.url }
        } finally { c.disconnect() }
    }

    suspend fun complete(key: String, model: String, system: String, msgs: List<Msg>, maxTokens: Int = 2048): String =
        completeFull(key, model, system, msgs, maxTokens).first

    suspend fun validate(key: String, model: String) {
        complete(key, model, "", listOf(Msg("user", "Hi")), maxTokens = 8)
    }
}
