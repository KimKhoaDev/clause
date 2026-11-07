package com.example.claude.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.example.claude.agent.AgentStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Repo {
    private lateinit var dir: File
    private lateinit var secure: SharedPreferences
    private lateinit var plain: SharedPreferences

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    private val _schedules = MutableStateFlow<List<Schedule>>(emptyList())
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _memories = MutableStateFlow<List<String>>(emptyList())
    private val _instructions = MutableStateFlow("")
    private val _memoryOn = MutableStateFlow(true)
    private val _apiKey = MutableStateFlow<String?>(null)
    private val _model = MutableStateFlow(Models.all.first().id)
    private val _style = MutableStateFlow("Normal")
    private val _webDefault = MutableStateFlow(false)
    private val _queue = MutableStateFlow<List<QueuedSend>>(emptyList())
    private val _provider = MutableStateFlow("anthropic")
    private val _customs = MutableStateFlow<List<Provider>>(emptyList())
    private val _ui = MutableStateFlow(UiPrefs())
    private val _agent = MutableStateFlow(AgentPrefs())
    private val _syncUri = MutableStateFlow<String?>(null)
    private val _lastSync = MutableStateFlow(0L)

    val chats: StateFlow<List<Chat>> = _chats
    val schedules: StateFlow<List<Schedule>> = _schedules
    val projects: StateFlow<List<Project>> = _projects
    val memories: StateFlow<List<String>> = _memories
    val instructions: StateFlow<String> = _instructions
    val memoryOn: StateFlow<Boolean> = _memoryOn
    val apiKey: StateFlow<String?> = _apiKey
    val model: StateFlow<String> = _model
    val style: StateFlow<String> = _style
    val webDefault: StateFlow<Boolean> = _webDefault
    val queue: StateFlow<List<QueuedSend>> = _queue
    val provider: StateFlow<String> = _provider
    val customs: StateFlow<List<Provider>> = _customs
    val ui: StateFlow<UiPrefs> = _ui
    val agent: StateFlow<AgentPrefs> = _agent
    val syncUri: StateFlow<String?> = _syncUri
    val lastSync: StateFlow<Long> = _lastSync

    @Synchronized
    fun init(ctx: Context) {
        if (this::dir.isInitialized) return
        val app = ctx.applicationContext
        dir = app.filesDir
        val mk = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        secure = EncryptedSharedPreferences.create(
            app, "secure", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        plain = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        _customs.value = runCatching {
            val a = JSONArray(plain.getString("customs", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Provider(it.getString("id"), it.getString("name"), ApiKind.OPENAI, it.getString("base"), it.getString("model"), "API key", it.optBoolean("vision", true), false, true, "Custom OpenAI-compatible endpoint") } }
        }.getOrDefault(emptyList())
        _provider.value = plain.getString("provider", "anthropic")!!
        _apiKey.value = keyOf(_provider.value)
        _model.value = modelOf(_provider.value)
        _style.value = plain.getString("style", "Normal")!!
        _instructions.value = plain.getString("instructions", "")!!
        _memoryOn.value = plain.getBoolean("memory_on", true)
        _webDefault.value = plain.getBoolean("web_default", false)
        _memories.value = runCatching {
            val a = JSONArray(plain.getString("memories", "[]")); (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
        _chats.value = readChats()
        _schedules.value = readSchedules()
        _projects.value = readProjects()
        _queue.value = readQueue()
        _syncUri.value = plain.getString("sync_uri", null)
        _lastSync.value = plain.getLong("last_sync", 0)
        Sync.init(app)
        _ui.value = UiPrefs.fromJson(plain.getString("ui_prefs", null))
        _agent.value = AgentPrefs.fromJson(plain.getString("agent_prefs", null))
        AgentStore.init(app)
    }

    fun attDir(): File = File(dir, "att").also { it.mkdirs() }

    // ---- providers (Anthropic + OpenAI-compatible gateways)
    fun allProviders(): List<Provider> = Providers.builtins + _customs.value
    fun providerById(id: String): Provider = allProviders().firstOrNull { it.id == id } ?: Providers.builtins.first()
    fun activeProvider(): Provider = providerById(_provider.value)
    fun keyOf(id: String): String? = secure.getString("key_$id", null) ?: if (id == "anthropic") secure.getString("api_key", null) else null
    fun configured(): List<Provider> = allProviders().filter { keyOf(it.id) != null }
    fun baseUrlOf(p: Provider): String = plain.getString("base_${p.id}", null)?.takeIf { it.isNotBlank() } ?: p.baseUrl
    fun setBaseUrl(id: String, u: String) { plain.edit().putString("base_$id", u.trim().trimEnd('/')).apply() }
    fun modelOf(id: String): String = plain.getString("model_$id", null)
        ?: (if (id == "anthropic") plain.getString("model", null) else null) ?: providerById(id).defaultModel

    fun setKey(id: String, k: String?) {
        secure.edit().apply { if (k == null) { remove("key_$id"); if (id == "anthropic") remove("api_key") } else putString("key_$id", k) }.apply()
        if (id == _provider.value) {
            _apiKey.value = k
            if (k == null) configured().firstOrNull()?.let { setActiveProvider(it.id) }
        }
    }
    /** Sets/clears the key of the active provider (null signs out of it). */
    fun setApiKey(k: String?) = setKey(_provider.value, k)
    fun setActiveProvider(id: String) {
        plain.edit().putString("provider", id).apply()
        _provider.value = id; _apiKey.value = keyOf(id); _model.value = modelOf(id)
    }
    fun setModel(m: String) { plain.edit().putString("model_${_provider.value}", m).apply(); _model.value = m }
    fun addCustom(p: Provider) { saveCustoms(_customs.value + p) }
    fun removeCustom(id: String) {
        setKey(id, null); saveCustoms(_customs.value.filter { it.id != id })
        if (_provider.value == id) setActiveProvider(configured().firstOrNull()?.id ?: "anthropic")
    }
    private fun saveCustoms(l: List<Provider>) {
        _customs.value = l
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("base", it.baseUrl).put("model", it.defaultModel).put("vision", it.vision)) }
        plain.edit().putString("customs", a.toString()).apply()
    }
    fun setStyle(s: String) { plain.edit().putString("style", s).apply(); _style.value = s }
    fun setInstructions(s: String) { plain.edit().putString("instructions", s).apply(); _instructions.value = s; Sync.markDirty() }
    fun setMemoryOn(b: Boolean) { plain.edit().putBoolean("memory_on", b).apply(); _memoryOn.value = b }
    fun setUi(u: UiPrefs) { _ui.value = u; plain.edit().putString("ui_prefs", u.toJson().toString()).apply() }
    fun setAgent(a: AgentPrefs) { _agent.value = a; plain.edit().putString("agent_prefs", a.toJson().toString()).apply() }
    fun setSyncUri(u: String?) { plain.edit().apply { if (u == null) remove("sync_uri") else putString("sync_uri", u) }.apply(); _syncUri.value = u }
    fun setLastSync(t: Long) { plain.edit().putLong("last_sync", t).apply(); _lastSync.value = t }
    fun setWebDefault(b: Boolean) { plain.edit().putBoolean("web_default", b).apply(); _webDefault.value = b }

    // ---- memory
    private fun saveMemories(l: List<String>) {
        _memories.value = l
        plain.edit().putString("memories", JSONArray(l).toString()).apply(); Sync.markDirty()
    }
    fun addMemory(s: String) { val t = s.trim(); if (t.isNotEmpty() && t !in _memories.value) saveMemories(_memories.value + t) }
    fun removeMemory(s: String) = saveMemories(_memories.value - s)

    // ---- chats
    fun chat(id: String) = _chats.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsertChat(c: Chat) {
        _chats.value = (listOf(c) + _chats.value.filter { it.id != c.id }).sortedByDescending { it.updated }
        writeChats()
    }
    /** Update fields without bumping the "updated" time (rename / pin / move). */
    @Synchronized
    fun patchChat(id: String, f: (Chat) -> Chat) {
        _chats.value = _chats.value.map { if (it.id == id) f(it) else it }
        writeChats()
    }
    @Synchronized
    fun deleteChat(id: String) {
        chat(id)?.let(::deleteFiles)
        _chats.value = _chats.value.filter { it.id != id }; writeChats()
    }
    @Synchronized
    fun clearChats() {
        _chats.value.forEach(::deleteFiles)
        _chats.value = emptyList(); writeChats()
    }
    private fun deleteFiles(c: Chat) = c.msgs.forEach { m -> m.atts.forEach { runCatching { File(it.path).delete() } } }

    // ---- projects
    fun project(id: String) = _projects.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsertProject(p: Project) {
        val cur = _projects.value
        _projects.value = if (cur.any { it.id == p.id }) cur.map { if (it.id == p.id) p else it } else cur + p
        writeProjects()
    }
    @Synchronized
    fun deleteProject(id: String) {
        _projects.value = _projects.value.filter { it.id != id }; writeProjects()
        _chats.value = _chats.value.map { if (it.projectId == id) it.copy(projectId = null) else it }; writeChats()
    }

    // ---- queued (auto-send after a usage limit)
    fun queued(id: String) = _queue.value.firstOrNull { it.id == id }
    @Synchronized
    fun upsertQueued(q: QueuedSend) {
        val cur = _queue.value
        _queue.value = if (cur.any { it.id == q.id }) cur.map { if (it.id == q.id) q else it } else cur + q
        writeQueue()
    }
    @Synchronized
    fun deleteQueued(id: String) { _queue.value = _queue.value.filter { it.id != id }; writeQueue() }

    // ---- schedules
    fun schedule(id: String) = _schedules.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsertSchedule(s: Schedule) {
        val cur = _schedules.value
        _schedules.value = if (cur.any { it.id == s.id }) cur.map { if (it.id == s.id) s else it } else cur + s
        writeSchedules()
    }
    @Synchronized
    fun deleteSchedule(id: String) { _schedules.value = _schedules.value.filter { it.id != id }; writeSchedules() }

    // ---- persistence
    private fun chatsToJson(list: List<Chat>, withAtts: Boolean): JSONArray {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("title", c.title).put("updated", c.updated)
                .put("sched", c.fromSchedule).put("pin", c.pinned).put("proj", c.projectId ?: JSONObject.NULL)
                .put("msgs", JSONArray().also { a -> c.msgs.forEach { m ->
                    a.put(JSONObject().put("r", m.role).put("t", m.text).put("ts", m.time)
                        .put("a", JSONArray().also { aa -> if (withAtts) m.atts.forEach { x ->
                            aa.put(JSONObject().put("n", x.name).put("m", x.mime).put("p", x.path)) } })
                        .put("s", JSONArray().also { ss -> m.sources.forEach { x ->
                            ss.put(JSONObject().put("t", x.title).put("u", x.url)) } })
                        .put("raw", if (withAtts) (m.raw ?: JSONObject.NULL) else JSONObject.NULL)
                        .put("tl", JSONArray().also { tt -> m.tools.forEach { x ->
                            tt.put(JSONObject().put("id", x.id).put("n", x.name).put("s", x.summary).put("i", x.input)
                                .put("r", if (withAtts) x.result else x.result.take(300)).put("ok", x.ok).put("st", x.status).put("k", x.risk.name)) } }))
                } }))
        }
        return arr
    }
    private fun parseTools(a: JSONArray?): List<ToolStep> = if (a == null) emptyList() else (0 until a.length()).map { i ->
        a.getJSONObject(i).let { ToolStep(it.getString("id"), it.getString("n"), it.getString("s"), it.optString("i"), it.optString("r"), it.optBoolean("ok", true), it.optString("st", "done"), runCatching { Risk.valueOf(it.optString("k")) }.getOrDefault(Risk.SAFE)) }
    }
    private fun jsonToChats(arr: JSONArray): List<Chat> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i); val ma = o.getJSONArray("msgs")
        Chat(o.getString("id"), o.getString("title"),
            (0 until ma.length()).map { j ->
                val m = ma.getJSONObject(j)
                val aa = m.optJSONArray("a") ?: JSONArray()
                val ss = m.optJSONArray("s") ?: JSONArray()
                Msg(m.getString("r"), m.getString("t"), m.optLong("ts"),
                    (0 until aa.length()).map { k -> aa.getJSONObject(k).let { Attachment(it.getString("n"), it.getString("m"), it.getString("p")) } },
                    (0 until ss.length()).map { k -> ss.getJSONObject(k).let { Source(it.getString("t"), it.getString("u")) } },
                    tools = parseTools(m.optJSONArray("tl")), raw = if (m.isNull("raw")) null else m.optString("raw").ifBlank { null })
            },
            o.optLong("updated"), o.optBoolean("sched"), o.optBoolean("pin"),
            if (o.isNull("proj")) null else o.optString("proj"))
    }
    private fun projectsToJson(l: List<Project>) = JSONArray().also { arr ->
        l.forEach { p -> arr.put(JSONObject().put("id", p.id).put("name", p.name).put("ins", p.instructions).put("ts", p.updated)) }
    }
    private fun jsonToProjects(arr: JSONArray) = (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let { Project(it.getString("id"), it.getString("name"), it.getString("ins"), it.optLong("ts")) }
    }
    private fun schedulesToJson(l: List<Schedule>) = JSONArray().also { arr ->
        l.forEach { s -> arr.put(JSONObject().put("id", s.id).put("name", s.name).put("prompt", s.prompt).put("h", s.hour)
            .put("m", s.minute).put("rep", s.repeat.name).put("on", s.enabled).put("last", s.lastRun).put("ws", s.webSearch)) }
    }
    private fun jsonToSchedules(arr: JSONArray) = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Schedule(o.getString("id"), o.getString("name"), o.getString("prompt"), o.getInt("h"), o.getInt("m"),
            Repeat.valueOf(o.getString("rep")), o.optBoolean("on", true), o.optLong("last"), o.optBoolean("ws"))
    }

    private fun writeChats() { File(dir, "chats.json").writeText(chatsToJson(_chats.value, true).toString()); Sync.markDirty() }
    private fun readChats(): List<Chat> = runCatching {
        val f = File(dir, "chats.json"); if (!f.exists()) emptyList() else jsonToChats(JSONArray(f.readText()))
    }.getOrDefault(emptyList())

    private fun writeProjects() { File(dir, "projects.json").writeText(projectsToJson(_projects.value).toString()); Sync.markDirty() }
    private fun readProjects(): List<Project> = runCatching {
        val f = File(dir, "projects.json"); if (!f.exists()) emptyList() else jsonToProjects(JSONArray(f.readText()))
    }.getOrDefault(emptyList())

    private fun writeSchedules() { File(dir, "schedules.json").writeText(schedulesToJson(_schedules.value).toString()); Sync.markDirty() }
    private fun readSchedules(): List<Schedule> = runCatching {
        val f = File(dir, "schedules.json"); if (!f.exists()) emptyList() else jsonToSchedules(JSONArray(f.readText()))
    }.getOrDefault(emptyList())

    private fun writeQueue() {
        val arr = JSONArray()
        _queue.value.forEach { q -> arr.put(JSONObject().put("id", q.id).put("chat", q.chatId).put("at", q.at).put("web", q.web).put("n", q.attempts)) }
        File(dir, "queue.json").writeText(arr.toString())
    }
    private fun readQueue(): List<QueuedSend> = runCatching {
        val f = File(dir, "queue.json"); if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { QueuedSend(it.getString("id"), it.getString("chat"), it.getLong("at"), it.optBoolean("web"), it.optInt("n")) } }
    }.getOrDefault(emptyList())

    // ---- sync snapshot (API key and attachments are never exported)
    fun exportJson(): String = JSONObject().put("v", 1).put("saved", System.currentTimeMillis())
        .put("chats", chatsToJson(_chats.value, false)).put("projects", projectsToJson(_projects.value))
        .put("schedules", schedulesToJson(_schedules.value)).put("memories", JSONArray(_memories.value))
        .put("instructions", _instructions.value).toString()

    /** Merges a snapshot into local data. Newer item wins; nothing local is deleted. Returns true if anything changed. */
    @Synchronized
    fun importMerge(json: String): Boolean {
        var changed = false
        Sync.applying = true
        try {
            val o = JSONObject(json)
            val chats = _chats.value.associateBy { it.id }.toMutableMap()
            jsonToChats(o.optJSONArray("chats") ?: JSONArray()).forEach { r ->
                val l = chats[r.id]
                if (l == null || r.updated > l.updated) { chats[r.id] = r; changed = true }
            }
            if (changed) { _chats.value = chats.values.sortedByDescending { it.updated }; writeChats() }

            var pc = false
            val projects = _projects.value.associateBy { it.id }.toMutableMap()
            jsonToProjects(o.optJSONArray("projects") ?: JSONArray()).forEach { r ->
                val l = projects[r.id]
                if (l == null || r.updated > l.updated) { projects[r.id] = r; pc = true }
            }
            if (pc) { _projects.value = projects.values.toList(); writeProjects(); changed = true }

            var sc = false
            val scheds = _schedules.value.associateBy { it.id }.toMutableMap()
            jsonToSchedules(o.optJSONArray("schedules") ?: JSONArray()).forEach { r -> if (r.id !in scheds) { scheds[r.id] = r; sc = true } }
            if (sc) { _schedules.value = scheds.values.toList(); writeSchedules(); changed = true }

            val mem = o.optJSONArray("memories")
            if (mem != null) {
                val extra = (0 until mem.length()).map { mem.getString(it) }.filter { it !in _memories.value }
                if (extra.isNotEmpty()) { saveMemories(_memories.value + extra); changed = true }
            }
            val ins = o.optString("instructions")
            if (_instructions.value.isBlank() && ins.isNotBlank()) { setInstructions(ins); changed = true }
        } finally { Sync.applying = false }
        return changed
    }
}
