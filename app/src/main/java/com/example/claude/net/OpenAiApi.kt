package com.example.claude.net

import com.example.claude.data.Msg
import com.example.claude.data.Provider
import com.example.claude.data.Repo
import com.example.claude.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Client for OpenAI-compatible endpoints (OpenRouter, Kilo Gateway, Gemini, OpenAI, Groq, Ollama, ...). */
object OpenAiApi {
    private fun open(p: Provider, key: String, path: String, post: Boolean): HttpURLConnection =
        (URL(Repo.baseUrlOf(p).trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (post) "POST" else "GET"
            connectTimeout = 20_000; readTimeout = 180_000
            if (post) doOutput = true
            if (key.isNotBlank() && key != "none") setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("content-type", "application/json")
            if (p.id == "openrouter") setRequestProperty("X-Title", "Clause")
        }

    private fun errorOf(c: HttpURLConnection): ApiException {
        val raw = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        val obj = runCatching { if (raw.trimStart().startsWith("[")) JSONArray(raw).getJSONObject(0) else JSONObject(raw) }.getOrNull()
        val e = obj?.opt("error")
        val (msg, type) = when (e) {
            is JSONObject -> e.optString("message").ifBlank { raw.take(300) } to e.optString("type").ifBlank { e.optString("code") }
            is String -> e to ""
            else -> raw.ifBlank { "HTTP ${c.responseCode}" }.take(300) to ""
        }
        val retry = c.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.toLong()
        return ApiException(msg, c.responseCode, retry, type)
    }

    private fun textPart(t: String) = JSONObject().put("type", "text").put("text", t)
    private fun imagePart(b: JSONObject): JSONObject {
        val s = b.getJSONObject("source")
        return JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${s.getString("media_type")};base64,${s.getString("data")}"))
    }
    private fun userMessage(parts: JSONArray): JSONObject {
        val allText = (0 until parts.length()).all { parts.getJSONObject(it).optString("type") == "text" }
        return JSONObject().put("role", "user").put("content",
            if (allText) (0 until parts.length()).joinToString("\n\n") { parts.getJSONObject(it).getString("text") } else parts)
    }

    /** Converts canonical (Anthropic-format) messages, tool calls and tool results into OpenAI chat format. */
    fun fromAnthropic(system: String, anth: JSONArray, vision: Boolean): JSONArray {
        val out = JSONArray()
        if (system.isNotBlank()) out.put(JSONObject().put("role", "system").put("content", system))
        for (k in 0 until anth.length()) {
            val m = anth.getJSONObject(k); val role = m.getString("role"); val c = m.get("content")
            if (c is String) { out.put(JSONObject().put("role", role).put("content", c)); continue }
            val blocks = c as JSONArray
            val list = (0 until blocks.length()).map { blocks.getJSONObject(it) }
            if (role == "assistant") {
                val text = list.filter { it.optString("type") == "text" }.joinToString("\n\n") { it.optString("text") }
                val calls = JSONArray()
                list.filter { it.optString("type") == "tool_use" }.forEach {
                    calls.put(JSONObject().put("id", it.getString("id")).put("type", "function")
                        .put("function", JSONObject().put("name", it.getString("name")).put("arguments", it.optJSONObject("input")?.toString() ?: "{}")))
                }
                val o = JSONObject().put("role", "assistant").put("content", if (text.isBlank() && calls.length() > 0) JSONObject.NULL else text)
                if (calls.length() > 0) o.put("tool_calls", calls)
                out.put(o)
            } else {
                val images = mutableListOf<JSONObject>()
                list.filter { it.optString("type") == "tool_result" }.forEach { tr ->
                    val rc = tr.opt("content"); val sb = StringBuilder()
                    if (rc is JSONArray) for (j in 0 until rc.length()) {
                        val b = rc.getJSONObject(j)
                        when (b.optString("type")) { "text" -> sb.append(b.optString("text")).append('\n'); "image" -> images += b }
                    } else sb.append(rc?.toString().orEmpty())
                    out.put(JSONObject().put("role", "tool").put("tool_call_id", tr.getString("tool_use_id")).put("content", sb.toString().trim().ifBlank { "(no output)" }))
                }
                if (images.isNotEmpty() && vision) {
                    val parts = JSONArray().put(textPart("Image returned by the last tool:"))
                    images.forEach { parts.put(imagePart(it)) }
                    out.put(JSONObject().put("role", "user").put("content", parts))
                }
                val parts = JSONArray()
                list.filter { it.optString("type") != "tool_result" }.forEach { b ->
                    when (b.optString("type")) {
                        "text" -> parts.put(textPart(b.optString("text")))
                        "image" -> parts.put(if (vision) imagePart(b) else textPart("[image omitted: this model can't read images]"))
                        "document" -> parts.put(textPart("[PDF omitted: this provider can't read PDFs]"))
                    }
                }
                if (parts.length() > 0) out.put(userMessage(parts))
            }
        }
        return out
    }

    fun toolsFromAnthropic(tools: JSONArray): JSONArray = JSONArray().also { o ->
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            o.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.getString("name"))
                .put("description", t.optString("description")).put("parameters", t.getJSONObject("input_schema"))))
        }
    }

    private suspend fun streamRaw(p: Provider, key: String, model: String, messages: JSONArray, tools: JSONArray?, onText: (String) -> Unit): ClaudeApi.Turn =
        withContext(Dispatchers.IO) {
            val c = open(p, key, "/chat/completions", true)
            try {
                val payload = JSONObject().put("model", model).put("messages", messages).put("stream", true)
                if (tools != null && tools.length() > 0) payload.put("tools", tools)
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
                if (c.responseCode !in 200..299) throw errorOf(c)
                val text = StringBuilder()
                val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()
                val job = currentCoroutineContext()[Job]
                fun s(v: String?) = v?.takeIf { it.isNotEmpty() && it != "null" }
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (job?.isActive == false) break
                        if (!line.startsWith("data:")) continue
                        val d = line.removePrefix("data:").trim(); if (d == "[DONE]") break
                        val j = runCatching { JSONObject(d) }.getOrNull() ?: continue
                        j.optJSONObject("error")?.let { throw ApiException(it.optString("message", "Stream error"), 0, null, it.optString("type")) }
                        val delta = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                        s(delta.optString("content"))?.let { text.append(it); onText(it) }
                        delta.optJSONArray("tool_calls")?.let { tcs ->
                            for (t in 0 until tcs.length()) {
                                val tc = tcs.getJSONObject(t)
                                val e = calls.getOrPut(tc.optInt("index", t)) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                                s(tc.optString("id"))?.let { e.first.append(it) }
                                tc.optJSONObject("function")?.let { f -> s(f.optString("name"))?.let { e.second.append(it) }; s(f.optString("arguments"))?.let { e.third.append(it) } }
                            }
                        }
                    }
                }
                val blocks = JSONArray()
                if (text.isNotBlank()) blocks.put(textPart(text.toString()))
                calls.values.filter { it.second.isNotEmpty() }.forEach { (id, name, args) ->
                    blocks.put(JSONObject().put("type", "tool_use").put("id", id.toString().ifBlank { "call_" + UUID.randomUUID().toString().take(12) })
                        .put("name", name.toString()).put("input", runCatching { JSONObject(args.toString().ifBlank { "{}" }) }.getOrDefault(JSONObject())))
                }
                ClaudeApi.Turn(blocks, if (calls.isNotEmpty()) "tool_use" else "end_turn")
            } finally { c.disconnect() }
        }

    suspend fun streamTurn(p: Provider, key: String, model: String, system: String, anth: JSONArray, tools: JSONArray, onText: (String) -> Unit): ClaudeApi.Turn =
        streamRaw(p, key, model, fromAnthropic(system, anth, p.vision), toolsFromAnthropic(tools), onText)

    suspend fun stream(p: Provider, key: String, model: String, system: String, msgs: List<Msg>, onDelta: (String) -> Unit): String {
        val t = streamRaw(p, key, model, fromAnthropic(system, ClaudeApi.buildMessages(msgs), p.vision), null, onDelta)
        return (0 until t.content.length()).joinToString("") { t.content.getJSONObject(it).optString("text") }
    }

    suspend fun completeFull(p: Provider, key: String, model: String, system: String, msgs: List<Msg>): Pair<String, List<Source>> =
        withContext(Dispatchers.IO) {
            val c = open(p, key, "/chat/completions", true)
            try {
                val payload = JSONObject().put("model", model).put("stream", false)
                    .put("messages", fromAnthropic(system, ClaudeApi.buildMessages(msgs), p.vision))
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
                if (c.responseCode !in 200..299) throw errorOf(c)
                val msg = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val content = msg.opt("content")
                val text = when (content) {
                    is JSONArray -> (0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") }
                    is String -> content
                    else -> ""
                }
                text to emptyList()
            } finally { c.disconnect() }
        }

    suspend fun fetchModels(p: Provider, key: String): List<String> = withContext(Dispatchers.IO) {
        val c = open(p, key, "/models", false)
        try {
            if (c.responseCode !in 200..299) throw errorOf(c)
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val arr = j.optJSONArray("data") ?: j.optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.removePrefix("models/")?.takeIf { s -> s.isNotBlank() } }.distinct().sorted().take(600)
        } finally { c.disconnect() }
    }

    suspend fun validate(p: Provider, key: String, model: String) {
        try { fetchModels(p, key) }
        catch (e: ApiException) {
            if (e.code == 401 || e.code == 403) throw e
            completeFull(p, key, model, "", listOf(Msg("user", "Hi")))
        }
    }
}
