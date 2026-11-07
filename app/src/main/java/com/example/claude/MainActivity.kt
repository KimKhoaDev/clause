package com.example.claude

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.claude.agent.ShizukuState
import com.example.claude.data.Repo
import com.example.claude.data.Sync
import com.example.claude.ui.App
import com.example.claude.ui.ChatController
import com.example.claude.ui.ClaudeTheme
import com.example.claude.work.Scheduler

class MainActivity : ComponentActivity() {
    private var pendingChat by mutableStateOf<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.init(this)
        ShizukuState.init(this)
        Scheduler.ensureChannel(this)
        Scheduler.rescheduleAll(this)
        pendingChat = intent?.getStringExtra(Scheduler.EXTRA_CHAT)

        setContent {
            ClaudeTheme {
                val scope = rememberCoroutineScope()
                val ctl = remember { ChatController(scope, applicationContext) }
                App(ctl, pendingChat, { pendingChat = null }, ::requestPerms)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Sync.syncSoon()
        ShizukuState.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingChat = intent.getStringExtra(Scheduler.EXTRA_CHAT)
    }

    private fun requestPerms() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val am = getSystemService(android.app.AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName")))
    }
}
