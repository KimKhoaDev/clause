package com.example.claude.data

import android.content.Context
import android.net.Uri
import com.example.claude.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cross-device sync without a server: the app keeps a JSON snapshot in a file the user picks
 * (put it in Google Drive / Nextcloud / Dropbox / Syncthing and the cloud app moves it between devices).
 * On every sync: read file -> merge (newer wins) -> write merged snapshot back.
 */
object Sync {
    private lateinit var app: Context
    @Volatile var applying = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null
    private var lastRun = 0L

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun markDirty() {
        if (applying || !::app.isInitialized || Repo.syncUri.value == null) return
        job?.cancel()
        job = scope.launch { delay(3000); syncNow() }
    }

    /** Fire-and-forget sync, throttled (used on app resume and from Settings). */
    fun syncSoon(force: Boolean = false) {
        if (!::app.isInitialized || Repo.syncUri.value == null) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < 15_000) return
        lastRun = now
        scope.launch { syncNow() }
    }

    suspend fun syncNow(): Result<Unit> = mutex.withLock {
        runCatching {
            val uriStr = Repo.syncUri.value ?: return@runCatching
            val uri = Uri.parse(uriStr)
            val cr = app.contentResolver
            val remote = cr.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (remote.isNotBlank() && Repo.importMerge(remote)) Repo.schedules.value.forEach { Scheduler.schedule(app, it) }
            cr.openOutputStream(uri, "wt")?.use { it.write(Repo.exportJson().toByteArray()) }
            Repo.setLastSync(System.currentTimeMillis())
        }
    }
}
