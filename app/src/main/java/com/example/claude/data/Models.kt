package com.example.claude.data

import org.json.JSONArray
import org.json.JSONObject

enum class Repeat(val label: String) { ONCE("Once"), DAILY("Every day"), WEEKDAYS("Weekdays") }

data class Attachment(val name: String, val mime: String, val path: String)
data class Source(val title: String, val url: String)

enum class Risk { SAFE, MODERATE, DANGEROUS }

/** One device action Claude took (or tried) while answering. */
data class ToolStep(
    val id: String, val name: String, val summary: String, val input: String,
    val result: String = "", val ok: Boolean = true, val status: String = "done", val risk: Risk = Risk.SAFE,
)

data class Msg(
    val role: String,
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val atts: List<Attachment> = emptyList(),
    val sources: List<Source> = emptyList(),
    val tools: List<ToolStep> = emptyList(),
    /** API-format messages (tool_use / tool_result) produced during an agent turn, used to rebuild context. */
    val raw: String? = null,
)

data class Chat(
    val id: String,
    val title: String,
    val msgs: List<Msg>,
    val updated: Long = System.currentTimeMillis(),
    val fromSchedule: Boolean = false,
    val pinned: Boolean = false,
    val projectId: String? = null,
)

data class Project(val id: String, val name: String, val instructions: String, val updated: Long = System.currentTimeMillis())

data class Schedule(
    val id: String,
    val name: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val repeat: Repeat,
    val enabled: Boolean = true,
    val lastRun: Long = 0,
    val webSearch: Boolean = false,
)

data class QueuedSend(val id: String, val chatId: String, val at: Long, val web: Boolean, val attempts: Int = 0)

data class ModelOption(val id: String, val label: String)

object Models {
    val all = listOf(
        ModelOption("claude-sonnet-5-5", "Sonnet 5.5"),
        ModelOption("claude-opus-5-5", "Opus 5.5"),
        ModelOption("claude-haiku-5-5", "Haiku 5.5"),
    )
    const val TITLE_MODEL = "claude-haiku-5-5"
    fun label(id: String) = all.firstOrNull { it.id == id }?.label ?: id
}

object Styles {
    val all = linkedMapOf(
        "Normal" to "",
        "Concise" to "Be concise. Keep answers short and to the point.",
        "Explanatory" to "Explain step by step, teach the underlying ideas, and use examples.",
        "Formal" to "Use a formal, professional tone.",
    )
}

/** Builds the system prompt from style + custom instructions + memory + project. */
object Prompts {
    const val ARTIFACT_HINT = "When asked to build a web page, small app, game, chart or interactive demo, output ONE complete self-contained HTML document (inline CSS and JS, no external resources) in a single ```html code block, with a short descriptive <title>. When asked to draw or illustrate something, output ONE complete SVG (with a viewBox, no external resources) in a single ```svg code block (include a <title>). Add at most a short caption outside the block."

    fun system(projectId: String?): String = buildList {
        add("You are Claude, made by Anthropic, running inside an Android app called Clause.")
        add(ARTIFACT_HINT)
        Styles.all[Repo.style.value]?.takeIf { it.isNotBlank() }?.let(::add)
        Repo.instructions.value.takeIf { it.isNotBlank() }?.let { add("The user's custom instructions:\n$it") }
        if (Repo.memoryOn.value && Repo.memories.value.isNotEmpty())
            add("Things to remember about the user:\n" + Repo.memories.value.joinToString("\n") { "- $it" })
        projectId?.let(Repo::project)?.let { add("This chat belongs to the project \"${it.name}\". Project instructions:\n${it.instructions}") }
    }.joinToString("\n\n")
}

/** Look & feel the user can tune, including every liquid-glass parameter. */
data class UiPrefs(
    val themeMode: Int = 0,            // 0 system, 1 light, 2 dark
    val accent: Int = 0xFFD97757.toInt(),
    val replyFont: Int = 0,            // 0 serif, 1 sans, 2 mono
    val textScale: Float = 1f,
    val aura: Float = 1f,              // background glow strength
    val blur: Float = 6f,              // dp
    val lensH: Float = 14f,            // refraction edge height, dp
    val lensAmt: Float = 28f,          // refraction strength, dp
    val tint: Float = 0.4f,            // glass surface opacity
    val vibrancy: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("tm", themeMode).put("ac", accent).put("rf", replyFont).put("ts", textScale.toDouble())
        .put("au", aura.toDouble()).put("bl", blur.toDouble()).put("lh", lensH.toDouble()).put("la", lensAmt.toDouble())
        .put("ti", tint.toDouble()).put("vi", vibrancy)
    companion object {
        fun fromJson(s: String?): UiPrefs = runCatching {
            val o = JSONObject(s!!); val d = UiPrefs()
            UiPrefs(o.optInt("tm", d.themeMode), o.optInt("ac", d.accent), o.optInt("rf", d.replyFont), o.optDouble("ts", d.textScale.toDouble()).toFloat(),
                o.optDouble("au", d.aura.toDouble()).toFloat(), o.optDouble("bl", d.blur.toDouble()).toFloat(), o.optDouble("lh", d.lensH.toDouble()).toFloat(),
                o.optDouble("la", d.lensAmt.toDouble()).toFloat(), o.optDouble("ti", d.tint.toDouble()).toFloat(), o.optBoolean("vi", d.vibrancy))
        }.getOrDefault(UiPrefs())
    }
}

/** Device-control (agent) settings. mode: 0 ask every time, 1 auto-approve safe actions, 2 full auto. */
data class AgentPrefs(
    val mode: Int = 0,
    val maxSteps: Int = 20,
    val roots: List<String> = listOf("/sdcard"),
    val localFallback: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("m", mode).put("ms", maxSteps).put("r", JSONArray(roots)).put("lf", localFallback)
    companion object {
        fun fromJson(s: String?): AgentPrefs = runCatching {
            val o = JSONObject(s!!); val r = o.getJSONArray("r")
            AgentPrefs(o.optInt("m"), o.optInt("ms", 20), (0 until r.length()).map { r.getString(it) }, o.optBoolean("lf", true))
        }.getOrDefault(AgentPrefs())
    }
}
