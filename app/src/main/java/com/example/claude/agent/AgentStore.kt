package com.example.claude.agent

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A file the agent modified, with a backup so the change can be undone (idea from Mobile-Harness checkpoints). */
data class Change(
    val id: String, val chatId: String, val path: String, val tool: String, val existed: Boolean,
    val backup: String?, val time: Long, val status: String, val note: String,
)
data class LogEntry(val time: Long, val tool: String, val summary: String, val risk: String, val decision: String, val ok: Boolean, val snippet: String)

object AgentStore {
    private const val MAX_BACKUP = 5L * 1024 * 1024
    private lateinit var dir: File
    private val _changes = MutableStateFlow<List<Change>>(emptyList())
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val changes: StateFlow<List<Change>> = _changes
    val log: StateFlow<List<LogEntry>> = _log

    fun init(ctx: Context) {
        if (this::dir.isInitialized) return
        dir = ctx.filesDir
        _changes.value = runCatching {
            val a = JSONArray(File(dir, "changes.json").readText())
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Change(it.getString("id"), it.optString("chat"), it.getString("p"), it.getString("t"), it.getBoolean("e"), if (it.isNull("b")) null else it.getString("b"), it.getLong("ts"), it.getString("s"), it.optString("n")) } }
        }.getOrDefault(emptyList())
        _log.value = runCatching {
            val a = JSONArray(File(dir, "agent_log.json").readText())
            (0 until a.length()).map { i -> a.getJSONObject(i).let { LogEntry(it.getLong("ts"), it.getString("t"), it.getString("s"), it.getString("r"), it.getString("d"), it.getBoolean("ok"), it.optString("x")) } }
        }.getOrDefault(emptyList())
    }

    private fun saveChanges() {
        val a = JSONArray()
        _changes.value.forEach { c -> a.put(JSONObject().put("id", c.id).put("chat", c.chatId).put("p", c.path).put("t", c.tool).put("e", c.existed).put("b", c.backup ?: JSONObject.NULL).put("ts", c.time).put("s", c.status).put("n", c.note)) }
        File(dir, "changes.json").writeText(a.toString())
    }
    private fun saveLog() {
        val a = JSONArray()
        _log.value.forEach { l -> a.put(JSONObject().put("ts", l.time).put("t", l.tool).put("s", l.summary).put("r", l.risk).put("d", l.decision).put("ok", l.ok).put("x", l.snippet)) }
        File(dir, "agent_log.json").writeText(a.toString())
    }

    @Synchronized fun log(e: LogEntry) { _log.value = (listOf(e) + _log.value).take(300); saveLog() }
    @Synchronized fun clearLog() { _log.value = emptyList(); saveLog() }

    /** Call before the agent writes, overwrites, moves or deletes [path]. */
    suspend fun snapshot(chatId: String, path: String, tool: String) {
        val id = UUID.randomUUID().toString()
        val st = Shell.exec("stat -c '%F|%s' ${shq(path)} 2>/dev/null")
        val change = if (!st.ok) Change(id, chatId, path, tool, false, null, System.currentTimeMillis(), "pending", "new file")
        else {
            val parts = st.text.trim().split("|")
            val size = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            if (parts[0].contains("regular") && size <= MAX_BACKUP) {
                val r = Shell.exec("cat ${shq(path)}")
                if (r.ok) { File(dir, "checkpoints").mkdirs(); File(dir, "checkpoints/$id").writeBytes(r.out)
                    Change(id, chatId, path, tool, true, id, System.currentTimeMillis(), "pending", "original saved") }
                else Change(id, chatId, path, tool, true, null, System.currentTimeMillis(), "manual", "couldn't back up")
            } else Change(id, chatId, path, tool, true, null, System.currentTimeMillis(), "manual",
                if (parts[0].contains("directory")) "directory — can't be undone" else "too large to back up")
        }
        synchronized(this) { _changes.value = listOf(change) + _changes.value; saveChanges() }
    }

    @Synchronized private fun setStatus(id: String, s: String) { _changes.value = _changes.value.map { if (it.id == id) it.copy(status = s) else it }; saveChanges() }
    fun keep(id: String) = setStatus(id, "kept")

    suspend fun undo(id: String): String {
        val c = _changes.value.firstOrNull { it.id == id } ?: return "Not found"
        if (c.status != "pending") return "Nothing to undo"
        val r = when {
            !c.existed -> Shell.exec("rm -f ${shq(c.path)}")
            c.backup != null -> {
                val b = File(dir, "checkpoints/${c.backup}").readBytes()
                Shell.exec("mkdir -p \"$(dirname ${shq(c.path)})\" && base64 -d > ${shq(c.path)}", stdin = (Base64.encodeToString(b, Base64.NO_WRAP) + "\n").toByteArray())
            }
            else -> return "No backup available"
        }
        if (r.ok) setStatus(id, "undone")
        return if (r.ok) "Restored ${c.path}" else "Undo failed: ${r.err.ifBlank { "exit ${r.code}" }}"
    }
}
