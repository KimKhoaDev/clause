package com.example.claude.net

import com.example.claude.data.ApiKind
import com.example.claude.data.Models
import com.example.claude.data.Msg
import com.example.claude.data.Provider
import com.example.claude.data.Repo
import com.example.claude.data.Source
import org.json.JSONArray

/** Picks the right client for the active provider. Conversations stay in one canonical (Anthropic-style) format. */
object AI {
    private fun p() = Repo.activeProvider()
    fun supportsWeb() = p().kind == ApiKind.ANTHROPIC
    fun titleModel() = if (p().kind == ApiKind.ANTHROPIC) Models.TITLE_MODEL else Repo.model.value

    suspend fun stream(
        key: String, model: String, system: String, msgs: List<Msg>, web: Boolean,
        onDelta: (String) -> Unit, onStatus: (String?) -> Unit, onSources: (List<Source>) -> Unit,
    ): String = if (p().kind == ApiKind.ANTHROPIC) ClaudeApi.stream(key, model, system, msgs, web, onDelta, onStatus, onSources)
    else OpenAiApi.stream(p(), key, model, system, msgs, onDelta)

    suspend fun streamTurn(key: String, model: String, system: String, messages: JSONArray, tools: JSONArray, onText: (String) -> Unit): ClaudeApi.Turn =
        if (p().kind == ApiKind.ANTHROPIC) ClaudeApi.streamTurn(key, model, system, messages, tools, onText = onText)
        else OpenAiApi.streamTurn(p(), key, model, system, messages, tools, onText)

    suspend fun completeFull(key: String, model: String, system: String, msgs: List<Msg>, maxTokens: Int = 2048, web: Boolean = false): Pair<String, List<Source>> =
        if (p().kind == ApiKind.ANTHROPIC) ClaudeApi.completeFull(key, model, system, msgs, maxTokens, web)
        else OpenAiApi.completeFull(p(), key, model, system, msgs)

    suspend fun complete(key: String, model: String, system: String, msgs: List<Msg>, maxTokens: Int = 2048): String =
        completeFull(key, model, system, msgs, maxTokens).first

    suspend fun validate(pr: Provider, key: String, model: String) {
        if (pr.kind == ApiKind.ANTHROPIC) ClaudeApi.validate(key, model) else OpenAiApi.validate(pr, key, model)
    }

    suspend fun fetchModels(pr: Provider, key: String): List<String> =
        if (pr.kind == ApiKind.ANTHROPIC) Models.all.map { it.id } else OpenAiApi.fetchModels(pr, key)
}
