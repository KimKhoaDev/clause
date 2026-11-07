package com.example.claude.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku

enum class ShizukuStatus(val label: String) {
    NotInstalled("Shizuku isn't installed"),
    NotRunning("Shizuku isn't running — start it with Wireless debugging"),
    NeedsPermission("Shizuku is running — permission needed"),
    Ready("Shizuku is connected"),
}

/** Tracks Shizuku (started over Wireless debugging) and exposes whether we can run commands as the shell user. */
object ShizukuState {
    const val PKG = "moe.shizuku.privileged.api"
    private const val REQ = 4711
    private lateinit var app: Context

    var status by mutableStateOf(ShizukuStatus.NotRunning); private set
    val ready: Boolean get() = status == ShizukuStatus.Ready

    private val onBinder = Shizuku.OnBinderReceivedListener { refresh() }
    private val onDead = Shizuku.OnBinderDeadListener { refresh() }
    private val onPerm = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun init(ctx: Context) {
        if (this::app.isInitialized) { refresh(); return }
        app = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPerm)
        refresh()
    }

    fun installed(): Boolean = runCatching { app.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    fun refresh() {
        status = runCatching {
            when {
                !Shizuku.pingBinder() -> if (installed()) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled
                Shizuku.isPreV11() -> ShizukuStatus.NotRunning
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuStatus.Ready
                else -> ShizukuStatus.NeedsPermission
            }
        }.getOrDefault(ShizukuStatus.NotRunning)
    }

    fun requestPermission() {
        runCatching { if (Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQ) else openShizuku(app) }
    }

    fun openShizuku(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest"))
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openDeveloperOptions(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
