package com.example.claude.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Xml
import com.example.claude.data.Repo
import com.example.claude.data.Risk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class ToolOutput(val text: String, val ok: Boolean = true, val status: String = "done", val imageB64: String? = null)

/** Everything Claude can do on the phone. Commands run through Shizuku (shell uid) — started via Wireless debugging. */
object AgentTools {
    private const val MAX_OUT = 12_000
    private const val MAX_DOWNLOAD = 600L * 1024 * 1024
    private val protectedPaths = setOf("/", "/sdcard", "/storage", "/data", "/system", "/vendor", "/product", "/proc", "/dev", "/etc", "/sdcard/Android")
    private val PKG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val NEVER_TOUCH = setOf("com.example.claude", ShizukuState.PKG)

    private fun cap(s: String): String =
        if (s.length <= MAX_OUT) s else s.take(MAX_OUT * 2 / 3) + "\n…[${s.length - MAX_OUT} characters omitted]…\n" + s.takeLast(MAX_OUT / 3)

    // ------------------------------------------------------------------ schemas
    private fun prop(type: String, desc: String, vararg enum: String): JSONObject =
        JSONObject().put("type", type).put("description", desc).also { if (enum.isNotEmpty()) it.put("enum", JSONArray(enum.toList())) }

    private fun tool(name: String, desc: String, required: List<String>, vararg props: Pair<String, JSONObject>): JSONObject =
        JSONObject().put("name", name).put("description", desc).put("input_schema", JSONObject().put("type", "object")
            .put("properties", JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } }).put("required", JSONArray(required)))

    val schemas: JSONArray by lazy {
        JSONArray().apply {
            put(tool("shell", "Run a shell command on the phone as the Shizuku shell user (uid 2000, not root). Use for anything the dedicated tools don't cover.", listOf("command"),
                "command" to prop("string", "sh command line"), "timeout_sec" to prop("integer", "max seconds (default 30, max 120)")))
            put(tool("read_file", "Read a text file (absolute path). Output is capped; use offset to page.", listOf("path"),
                "path" to prop("string", "absolute path, shared storage is /sdcard"), "offset" to prop("integer", "start byte"), "max_bytes" to prop("integer", "default 20000, max 100000")))
            put(tool("write_file", "Create or overwrite a file (a backup is kept so the user can undo).", listOf("path", "content"),
                "path" to prop("string", "absolute path"), "content" to prop("string", "full file content"), "append" to prop("boolean", "append instead of overwrite")))
            put(tool("edit_file", "Replace exact text in a file. old_string must match exactly once unless replace_all is true.", listOf("path", "old_string", "new_string"),
                "path" to prop("string", "absolute path"), "old_string" to prop("string", "text to find"), "new_string" to prop("string", "replacement"), "replace_all" to prop("boolean", "replace every occurrence")))
            put(tool("list_dir", "List a directory with sizes and dates.", listOf("path"), "path" to prop("string", "absolute directory path")))
            put(tool("file_op", "mkdir, copy, move or delete a path. Deleting a directory needs recursive=true and can't be undone.", listOf("op", "src"),
                "op" to prop("string", "operation", "mkdir", "copy", "move", "delete"), "src" to prop("string", "source/target path"), "dst" to prop("string", "destination for copy/move"), "recursive" to prop("boolean", "for directories")))
            put(tool("list_apps", "List launchable installed apps (name and package id), optionally filtered.", emptyList(), "query" to prop("string", "name or package substring")))
            put(tool("open_app", "Launch an app by package id.", listOf("package"), "package" to prop("string", "package id, e.g. com.android.settings")))
            put(tool("app_control", "Manage an app: info, force_stop, clear_data, disable, enable, uninstall, grant or revoke a permission.", listOf("action", "package"),
                "action" to prop("string", "what to do", "info", "force_stop", "clear_data", "disable", "enable", "uninstall", "grant", "revoke"),
                "package" to prop("string", "package id"), "permission" to prop("string", "android.permission.* for grant/revoke")))
            put(tool("settings", "Read or change Android settings (settings get/put/list).", listOf("op", "namespace"),
                "op" to prop("string", "operation", "get", "put", "list"), "namespace" to prop("string", "settings namespace", "system", "secure", "global"),
                "key" to prop("string", "setting key"), "value" to prop("string", "new value for put")))
            put(tool("input", "Simulate touch/keys: tap, swipe, long_press, text, key (HOME, BACK, RECENTS, ENTER, POWER, VOLUME_UP, VOLUME_DOWN, or any KEYCODE_*).", listOf("action"),
                "action" to prop("string", "gesture", "tap", "swipe", "long_press", "text", "key"),
                "x" to prop("integer", "x px"), "y" to prop("integer", "y px"), "x2" to prop("integer", "swipe end x"), "y2" to prop("integer", "swipe end y"),
                "duration_ms" to prop("integer", "swipe duration"), "text" to prop("string", "text to type"), "key" to prop("string", "key name")))
            put(tool("screen", "See the screen. ui_dump returns tappable elements with exact coordinates (prefer it); screenshot returns an image.", listOf("mode"),
                "mode" to prop("string", "what to capture", "ui_dump", "screenshot")))
            put(tool("device_info", "Model, Android version, battery, screen size and the foreground app.", emptyList()))
            put(tool("open_url", "Open an http(s), tel, geo or mailto link in the matching app.", listOf("url"), "url" to prop("string", "the URL")))
            put(tool("download", "Download a file from the internet to shared storage, optionally verifying a SHA-256 checksum.", listOf("url", "dest"),
                "url" to prop("string", "http(s) URL"), "dest" to prop("string", "absolute destination path, e.g. /sdcard/Download/Clause/app.apk"), "sha256" to prop("string", "expected SHA-256 hex")))
            put(tool("install_apk", "Silently install (or update) an APK that is already on storage.", listOf("path"), "path" to prop("string", "absolute path to the .apk")))
            put(tool("logcat", "Read recent system logs.", emptyList(), "lines" to prop("integer", "default 150, max 500"), "filter" to prop("string", "only lines containing this text")))
        }
    }

    // ------------------------------------------------------------------ paths & safety
    private fun norm(p: String): String? {
        if (!p.startsWith("/")) return null
        val n = java.nio.file.Paths.get(p).normalize().toString().ifEmpty { "/" }
        return n.replace(Regex("^/storage/emulated/0(?=/|$)"), "/sdcard")
    }
    private fun inRoots(p: String) = Repo.agent.value.roots.map { norm(it) ?: it }.any { p == it || p.startsWith("$it/") }
    private fun path(i: JSONObject, key: String = "path"): String = norm(i.optString(key)) ?: throw IllegalArgumentException("'$key' must be an absolute path")

    private fun dangerousRm(cmd: String): Boolean {
        val t = cmd.split(Regex("\\s+"))
        for (idx in t.indices) if (t[idx] == "rm" || t[idx].endsWith("/rm")) {
            val rest = t.drop(idx + 1).takeWhile { it != ";" && it != "&&" && it != "|" }
            val recursive = rest.any { it.startsWith("-") && (it.contains('r') || it.contains('R')) }
            if (recursive && rest.filter { !it.startsWith("-") }.any { norm(it.trim('"', '\'').removeSuffix("/*").removeSuffix("/").ifEmpty { "/" }) in protectedPaths || it == "*" || it == "~" }) return true
        }
        return false
    }

    /** Things refused even in Full-auto mode. */
    fun hardBlock(name: String, i: JSONObject): String? = runCatching {
        when (name) {
            "shell" -> {
                val c = i.optString("command").lowercase()
                when {
                    dangerousRm(c) -> "recursive delete of a top-level storage location"
                    Regex("\\bdd\\b.*\\bof=/dev/").containsMatchIn(c) || Regex("\\bmkfs").containsMatchIn(c) -> "writing to block devices / formatting"
                    c.contains("wipe_data") || c.contains("factory_reset") || c.contains("factoryreset") || c.contains("fastboot") -> "factory reset / bootloader commands"
                    else -> null
                }
            }
            "write_file", "edit_file" -> path(i).let { if (it in protectedPaths) "writing to a protected location" else null }
            "file_op" -> {
                val s = path(i, "src")
                if (i.optString("op") in setOf("delete", "move") && s in protectedPaths) "removing a protected location" else null
            }
            "app_control" -> if (i.optString("package") in NEVER_TOUCH && i.optString("action") in setOf("uninstall", "clear_data", "disable", "force_stop")) "that would disable Clause or Shizuku itself" else null
            else -> null
        }
    }.getOrNull()

    fun risk(name: String, i: JSONObject): Risk = runCatching {
        when (name) {
            "read_file", "list_dir", "list_apps", "device_info" -> Risk.SAFE
            "shell" -> if (isReadOnlyShell(i.optString("command"))) Risk.SAFE else Risk.DANGEROUS
            "write_file", "edit_file" -> if (inRoots(path(i))) Risk.MODERATE else Risk.DANGEROUS
            "file_op" -> {
                val op = i.optString("op"); val s = path(i, "src")
                val dstIn = i.optString("dst").let { it.isEmpty() || (norm(it)?.let(::inRoots) ?: false) }
                when {
                    op == "delete" && i.optBoolean("recursive") -> Risk.DANGEROUS
                    inRoots(s) && dstIn -> Risk.MODERATE
                    else -> Risk.DANGEROUS
                }
            }
            "open_app", "open_url", "input", "screen", "logcat" -> Risk.MODERATE
            "settings" -> when {
                i.optString("op") != "put" -> Risk.SAFE
                i.optString("namespace") == "system" -> Risk.MODERATE
                else -> Risk.DANGEROUS
            }
            "app_control" -> if (i.optString("action") in setOf("info", "force_stop")) Risk.MODERATE else Risk.DANGEROUS
            "download" -> if (inRoots(path(i, "dest"))) Risk.MODERATE else Risk.DANGEROUS
            "install_apk" -> Risk.DANGEROUS
            else -> Risk.DANGEROUS
        }
    }.getOrDefault(Risk.DANGEROUS)

    private val readOnlyCmds = setOf("ls", "cat", "pwd", "id", "getprop", "df", "du", "ps", "dumpsys", "wc", "head", "tail", "grep", "find", "stat", "date", "uname", "echo", "whoami", "uptime", "free", "top", "which", "ip", "ifconfig", "netstat", "logcat")
    private fun isReadOnlyShell(c: String): Boolean {
        if (Regex("[;&|<>`]|\\$\\(").containsMatchIn(c)) return false
        val t = c.trim().split(Regex("\\s+"))
        if (t.isEmpty()) return false
        if (t[0] == "pm" && t.getOrNull(1) == "list") return true
        if (t[0] == "settings" && t.getOrNull(1) in setOf("get", "list")) return true
        if (t[0] == "find" && (t.contains("-delete") || t.contains("-exec"))) return false
        return t[0] in readOnlyCmds
    }

    fun summary(name: String, i: JSONObject): String = runCatching {
        when (name) {
            "shell" -> "$ " + i.optString("command").take(140)
            "read_file" -> "Read " + i.optString("path")
            "write_file" -> "Write " + i.optString("path") + " (${i.optString("content").length} chars" + if (i.optBoolean("append")) ", append)" else ")"
            "edit_file" -> "Edit " + i.optString("path")
            "list_dir" -> "List " + i.optString("path")
            "file_op" -> i.optString("op").replaceFirstChar { it.uppercase() } + " " + i.optString("src") + (if (i.optString("dst").isNotEmpty()) " → " + i.optString("dst") else "")
            "list_apps" -> "List apps" + (i.optString("query").takeIf { it.isNotBlank() }?.let { " matching \"$it\"" } ?: "")
            "open_app" -> "Open " + i.optString("package")
            "app_control" -> i.optString("action").replace('_', ' ').replaceFirstChar { it.uppercase() } + " " + i.optString("package")
            "settings" -> "settings " + i.optString("op") + " " + i.optString("namespace") + " " + i.optString("key") + (if (i.has("value")) " = " + i.optString("value") else "")
            "input" -> "Input " + i.optString("action") + " " + listOf("x", "y", "x2", "y2").filter { i.has(it) }.joinToString(",") { i.optInt(it).toString() } + i.optString("text") + i.optString("key")
            "screen" -> "Look at screen (" + i.optString("mode") + ")"
            "device_info" -> "Read device info"
            "open_url" -> "Open " + i.optString("url").take(100)
            "download" -> "Download " + i.optString("url").take(80) + " → " + i.optString("dest")
            "install_apk" -> "Install " + i.optString("path")
            "logcat" -> "Read logcat"
            else -> name
        }
    }.getOrDefault(name)

    // ------------------------------------------------------------------ execution
    suspend fun run(ctx: Context, name: String, i: JSONObject, chatId: String): ToolOutput = try {
        when (name) {
            "shell" -> shell(i)
            "read_file" -> readFile(i)
            "write_file" -> writeFile(i, chatId)
            "edit_file" -> editFile(i, chatId)
            "list_dir" -> sh("ls -la ${shq(path(i))} | head -300")
            "file_op" -> fileOp(i, chatId)
            "list_apps" -> listApps(ctx, i.optString("query"))
            "open_app" -> openApp(i.optString("package"))
            "app_control" -> appControl(i)
            "settings" -> settings(i)
            "input" -> input(i)
            "screen" -> if (i.optString("mode") == "screenshot") screenshot() else uiDump()
            "device_info" -> sh("echo MODEL; getprop ro.product.model; echo ANDROID; getprop ro.build.version.release; echo SDK; getprop ro.build.version.sdk; echo BATTERY; dumpsys battery | grep -E 'level|status|powered'; echo SCREEN; wm size; wm density; echo FOREGROUND; dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'")
            "open_url" -> openUrl(i.optString("url"))
            "download" -> download(ctx, i, chatId)
            "install_apk" -> installApk(i)
            "logcat" -> logcat(i)
            else -> ToolOutput("Unknown tool: $name", false, "error")
        }
    } catch (e: Exception) { ToolOutput("Error: ${e.message}", false, "error") }

    private fun fromShell(r: ShellResult, okText: String = ""): ToolOutput {
        val body = (r.text + (if (r.err.isNotBlank()) "\n[stderr] " + r.err.trim() else "")).trim()
        return ToolOutput(cap(body.ifEmpty { if (r.ok) okText.ifEmpty { "(done)" } else "(exit ${r.code})" }) + if (!r.ok && body.isNotEmpty()) "\n[exit ${r.code}]" else "", r.ok, if (r.ok) "done" else "error")
    }
    private suspend fun sh(cmd: String, stdin: ByteArray? = null, t: Long = 30_000, okText: String = "") = fromShell(Shell.exec(cmd, stdin, t), okText)

    private suspend fun shell(i: JSONObject) = sh(i.getString("command"), t = i.optInt("timeout_sec", 30).coerceIn(1, 120) * 1000L)

    private suspend fun readFile(i: JSONObject): ToolOutput {
        val p = path(i); val off = i.optLong("offset", 0).coerceAtLeast(0); val max = i.optInt("max_bytes", 20_000).coerceIn(1, 100_000)
        val st = Shell.exec("stat -c '%F|%s' ${shq(p)}")
        if (!st.ok) return ToolOutput("No such file: $p", false, "error")
        if (!st.text.contains("regular")) return ToolOutput("$p is not a regular file (use list_dir)", false, "error")
        val size = st.text.trim().substringAfter('|').toLongOrNull() ?: 0
        val r = Shell.exec("tail -c +${off + 1} ${shq(p)} | head -c $max")
        if (r.out.any { it == 0.toByte() }) return ToolOutput("[binary file, $size bytes]")
        val shown = r.out.size
        return ToolOutput(r.text + if (off + shown < size) "\n…[showing bytes $off–${off + shown} of $size; pass offset=${off + shown} for more]" else "")
    }

    private suspend fun writeText(p: String, content: String, append: Boolean): ToolOutput {
        val b64 = (Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP) + "\n").toByteArray()
        val w = Shell.exec("mkdir -p \"$(dirname ${shq(p)})\" && base64 -d ${if (append) ">>" else ">"} ${shq(p)}", b64, 60_000)
        if (!w.ok) return ToolOutput("Write failed: ${w.err.ifBlank { "exit ${w.code}" }}", false, "error")
        val size = Shell.exec("stat -c %s ${shq(p)}").text.trim()
        return ToolOutput("Wrote $p ($size bytes)")
    }

    private suspend fun writeFile(i: JSONObject, chatId: String): ToolOutput {
        val p = path(i)
        AgentStore.snapshot(chatId, p, "write_file")
        return writeText(p, i.getString("content"), i.optBoolean("append"))
    }

    private suspend fun editFile(i: JSONObject, chatId: String): ToolOutput {
        val p = path(i)
        val st = Shell.exec("stat -c %s ${shq(p)}")
        if (!st.ok) return ToolOutput("No such file: $p", false, "error")
        if ((st.text.trim().toLongOrNull() ?: 0) > 2_000_000) return ToolOutput("File is larger than 2 MB; use shell/sed instead.", false, "error")
        val text = Shell.exec("cat ${shq(p)}").text
        val old = i.getString("old_string"); val new = i.getString("new_string")
        if (old.isEmpty()) return ToolOutput("old_string is empty", false, "error")
        val count = text.split(old).size - 1
        if (count == 0) return ToolOutput("old_string not found in $p", false, "error")
        if (count > 1 && !i.optBoolean("replace_all")) return ToolOutput("old_string matches $count times; add more context or set replace_all.", false, "error")
        AgentStore.snapshot(chatId, p, "edit_file")
        val r = writeText(p, text.replace(old, new), false)
        return if (r.ok) ToolOutput("Edited $p ($count replacement${if (count > 1) "s" else ""})") else r
    }

    private suspend fun fileOp(i: JSONObject, chatId: String): ToolOutput {
        val op = i.getString("op"); val s = path(i, "src")
        val d = if (op == "copy" || op == "move") path(i, "dst") else ""
        return when (op) {
            "mkdir" -> sh("mkdir -p ${shq(s)}", okText = "Created $s")
            "copy" -> { AgentStore.snapshot(chatId, d, "copy"); sh("cp -r ${shq(s)} ${shq(d)}", okText = "Copied $s → $d") }
            "move" -> { AgentStore.snapshot(chatId, s, "move"); AgentStore.snapshot(chatId, d, "move"); sh("mv ${shq(s)} ${shq(d)}", okText = "Moved $s → $d") }
            "delete" -> {
                AgentStore.snapshot(chatId, s, "delete")
                if (i.optBoolean("recursive")) sh("rm -rf ${shq(s)}", okText = "Deleted $s (can't be undone)") else sh("rm -f ${shq(s)}", okText = "Deleted $s")
            }
            else -> ToolOutput("Unknown op", false, "error")
        }
    }

    private suspend fun listApps(ctx: Context, q: String): ToolOutput = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }.sortedBy { it.first.lowercase() }
        ToolOutput(cap(apps.take(150).joinToString("\n") { "${it.first} — ${it.second}" }.ifEmpty { "No matching apps" }))
    }

    private suspend fun openApp(pkg: String): ToolOutput {
        require(PKG_RE.matches(pkg)) { "invalid package id" }
        val r = Shell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        return if (r.ok && !r.text.contains("No activities found")) ToolOutput("Launched $pkg") else ToolOutput("Couldn't launch $pkg: ${(r.text + r.err).trim().take(200)}", false, "error")
    }

    private suspend fun appControl(i: JSONObject): ToolOutput {
        val pkg = i.getString("package"); require(PKG_RE.matches(pkg)) { "invalid package id" }
        val perm = i.optString("permission"); if (perm.isNotEmpty()) require(Regex("^[A-Za-z0-9_.]+$").matches(perm)) { "invalid permission" }
        return when (i.getString("action")) {
            "info" -> sh("dumpsys package $pkg | head -60")
            "force_stop" -> sh("am force-stop $pkg", okText = "Stopped $pkg")
            "clear_data" -> sh("pm clear $pkg")
            "disable" -> sh("pm disable-user --user 0 $pkg")
            "enable" -> sh("pm enable $pkg")
            "uninstall" -> sh("pm uninstall --user 0 $pkg")
            "grant" -> sh("pm grant $pkg $perm", okText = "Granted $perm")
            "revoke" -> sh("pm revoke $pkg $perm", okText = "Revoked $perm")
            else -> ToolOutput("Unknown action", false, "error")
        }
    }

    private suspend fun settings(i: JSONObject): ToolOutput {
        val ns = i.getString("namespace"); require(ns in setOf("system", "secure", "global")) { "bad namespace" }
        val key = i.optString("key"); if (key.isNotEmpty()) require(Regex("^[A-Za-z0-9_.:-]+$").matches(key)) { "bad key" }
        return when (i.getString("op")) {
            "get" -> sh("settings get $ns $key")
            "list" -> sh("settings list $ns | head -200")
            "put" -> sh("settings put $ns $key ${shq(i.getString("value"))}", okText = "Set $ns/$key")
            else -> ToolOutput("Unknown op", false, "error")
        }
    }

    private suspend fun input(i: JSONObject): ToolOutput = when (i.getString("action")) {
        "tap" -> sh("input tap ${i.getInt("x")} ${i.getInt("y")}", okText = "Tapped")
        "long_press" -> sh("input swipe ${i.getInt("x")} ${i.getInt("y")} ${i.getInt("x")} ${i.getInt("y")} 800", okText = "Long-pressed")
        "swipe" -> sh("input swipe ${i.getInt("x")} ${i.getInt("y")} ${i.getInt("x2")} ${i.getInt("y2")} ${i.optInt("duration_ms", 300)}", okText = "Swiped")
        "text" -> sh("input text ${shq(i.getString("text").replace(" ", "%s"))}", okText = "Typed")
        "key" -> {
            val k = i.getString("key").uppercase().let { if (it.startsWith("KEYCODE_") || it.all(Char::isDigit)) it else "KEYCODE_$it" }
            require(Regex("^[A-Z0-9_]+$").matches(k)) { "bad key" }
            sh("input keyevent $k", okText = "Pressed $k")
        }
        else -> ToolOutput("Unknown action", false, "error")
    }

    private suspend fun screenshot(): ToolOutput {
        val r = Shell.exec("screencap -p", timeoutMs = 20_000)
        if (!r.ok || r.out.size < 200) return ToolOutput("Screenshot failed: ${r.err.ifBlank { "exit ${r.code}" }}", false, "error")
        return withContext(Dispatchers.IO) {
            val bmp = BitmapFactory.decodeByteArray(r.out, 0, r.out.size) ?: return@withContext ToolOutput("Couldn't decode screenshot", false, "error")
            val scale = minOf(1f, 1000f / maxOf(bmp.width, bmp.height))
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val bos = java.io.ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            ToolOutput("Screenshot shown at ${scaled.width}x${scaled.height}; the real screen is ${bmp.width}x${bmp.height} px (multiply coordinates by ${"%.2f".format(1f / scale)}). Prefer ui_dump for exact tap points.",
                imageB64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
        }
    }

    private suspend fun uiDump(): ToolOutput {
        var xml = ""
        repeat(2) {
            if (xml.isEmpty()) {
                val r = Shell.exec("uiautomator dump /dev/stdout", timeoutMs = 25_000)
                val t = r.text
                val s = t.indexOf("<?xml"); val e = t.indexOf("</hierarchy>")
                if (s >= 0 && e > s) xml = t.substring(s, e + 12)
            }
        }
        if (xml.isEmpty()) return ToolOutput("Couldn't read the UI (the screen may be animating or secure). Try again or use screenshot.", false, "error")
        return withContext(Dispatchers.IO) {
            val lines = mutableListOf<String>()
            val p = Xml.newPullParser(); p.setInput(StringReader(xml))
            var ev = p.eventType; var n = 0
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && n < 160) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "node") {
                    val text = p.getAttributeValue(null, "text").orEmpty(); val desc = p.getAttributeValue(null, "content-desc").orEmpty()
                    val clickable = p.getAttributeValue(null, "clickable") == "true"
                    val id = p.getAttributeValue(null, "resource-id").orEmpty().substringAfter(":id/")
                    val m = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(p.getAttributeValue(null, "bounds").orEmpty())
                    if ((text.isNotBlank() || desc.isNotBlank() || clickable) && m != null) {
                        val (l, t, r, b) = m.destructured
                        lines += "[$n] " + (if (text.isNotBlank()) "\"$text\" " else "") + (if (desc.isNotBlank()) "desc=\"$desc\" " else "") + (if (id.isNotBlank()) "id=$id " else "") +
                            (if (clickable) "clickable " else "") + "center=(${(l.toInt() + r.toInt()) / 2},${(t.toInt() + b.toInt()) / 2})"
                        n++
                    }
                }
                ev = p.next()
            }
            ToolOutput(cap(lines.joinToString("\n").ifEmpty { "No readable elements on screen" }))
        }
    }

    private suspend fun openUrl(url: String): ToolOutput {
        require(Regex("^(https?|tel|geo|mailto):", RegexOption.IGNORE_CASE).containsMatchIn(url)) { "only http(s), tel, geo and mailto links are allowed" }
        return sh("am start -a android.intent.action.VIEW -d ${shq(url)}", okText = "Opened")
    }

    private suspend fun download(ctx: Context, i: JSONObject, chatId: String): ToolOutput {
        val url = i.getString("url"); require(url.startsWith("http://") || url.startsWith("https://")) { "only http(s) URLs" }
        val dest = path(i, "dest"); val want = i.optString("sha256").lowercase().trim()
        val stage = ctx.externalCacheDir ?: return ToolOutput("External storage isn't available for staging", false, "error")
        val tmp = File(stage, "dl-${UUID.randomUUID()}.part")
        val (size, sha) = withContext(Dispatchers.IO) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true }
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                if (c.contentLengthLong > MAX_DOWNLOAD) throw IllegalStateException("file is larger than ${MAX_DOWNLOAD / 1024 / 1024} MB")
                val md = MessageDigest.getInstance("SHA-256"); var total = 0L
                c.inputStream.use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = inp.read(buf); if (n < 0) break; total += n
                        if (total > MAX_DOWNLOAD) throw IllegalStateException("download exceeded size limit"); md.update(buf, 0, n); out.write(buf, 0, n) }
                } }
                total to md.digest().joinToString("") { "%02x".format(it) }
            } finally { c.disconnect() }
        }
        if (want.isNotEmpty() && want != sha) { tmp.delete(); return ToolOutput("Checksum mismatch — expected $want but got $sha. File discarded.", false, "error") }
        AgentStore.snapshot(chatId, dest, "download")
        val mv = Shell.exec("mkdir -p \"$(dirname ${shq(dest)})\" && cp ${shq(tmp.absolutePath)} ${shq(dest)}", timeoutMs = 120_000)
        tmp.delete()
        return if (mv.ok) ToolOutput("Downloaded $size bytes to $dest\nSHA-256: $sha" + if (want.isNotEmpty()) " (verified)" else "")
        else ToolOutput("Saved download but couldn't move it to $dest: ${mv.err.ifBlank { "exit ${mv.code}" }}", false, "error")
    }

    private suspend fun installApk(i: JSONObject): ToolOutput {
        val p = path(i); require(p.endsWith(".apk", true)) { "path must end with .apk" }
        return sh("pm install -r -d ${shq(p)}", t = 120_000)
    }

    private suspend fun logcat(i: JSONObject): ToolOutput {
        val n = i.optInt("lines", 150).coerceIn(10, 500); val f = i.optString("filter")
        return sh("logcat -d -t $n" + if (f.isNotBlank()) " | grep -i -F ${shq(f)}" else "")
    }
}
