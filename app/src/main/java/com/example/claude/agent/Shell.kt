package com.example.claude.agent

import com.example.claude.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream

fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

class ShellResult(val code: Int, val out: ByteArray, val err: String, val backend: String) {
    val text: String get() = out.toString(Charsets.UTF_8)
    val ok: Boolean get() = code == 0
}

/** Runs commands as the Shizuku shell user (uid 2000). Falls back to this app's own limited shell if allowed. */
object Shell {
    // Shizuku 13 hides newProcess; reflection is the documented workaround for simple one-shot commands.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    fun available(): Boolean = ShizukuState.ready || Repo.agent.value.localFallback
    fun backendName(): String = if (ShizukuState.ready) "Shizuku (shell)" else "local app shell (limited)"

    private fun readCapped(s: InputStream, cap: Int = 8 * 1024 * 1024): ByteArray {
        val bos = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
        while (true) { val n = s.read(buf); if (n < 0) break; if (bos.size() < cap) bos.write(buf, 0, minOf(n, cap - bos.size())) }
        return bos.toByteArray()
    }

    suspend fun exec(cmd: String, stdin: ByteArray? = null, timeoutMs: Long = 30_000): ShellResult = withContext(Dispatchers.IO) {
        val viaShizuku = ShizukuState.ready
        if (!viaShizuku && !Repo.agent.value.localFallback)
            return@withContext ShellResult(127, ByteArray(0), "Shizuku isn't running or Clause lacks permission. Start Shizuku via Wireless debugging and grant access.", "none")
        val backend = if (viaShizuku) "shizuku" else "local"
        try {
            val proc: Process = if (viaShizuku) newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            else ProcessBuilder("sh", "-c", cmd).start()
            coroutineScope {
                val out = async { runCatching { readCapped(proc.inputStream) }.getOrDefault(ByteArray(0)) }
                val err = async { runCatching { readCapped(proc.errorStream, 64 * 1024).toString(Charsets.UTF_8) }.getOrDefault("") }
                runCatching { proc.outputStream.use { o -> if (stdin != null) o.write(stdin) } }
                val code = withTimeoutOrNull(timeoutMs) { runInterruptible { proc.waitFor() } }
                if (code == null) { runCatching { proc.destroy() }; ShellResult(124, out.await(), "Timed out after ${timeoutMs / 1000}s", backend) }
                else ShellResult(code, out.await(), err.await(), backend)
            }
        } catch (e: Exception) {
            ShellResult(126, ByteArray(0), (e.cause ?: e).message ?: "Failed to start shell", backend)
        }
    }
}
