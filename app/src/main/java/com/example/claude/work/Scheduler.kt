package com.example.claude.work

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.claude.MainActivity
import com.example.claude.data.Chat
import com.example.claude.data.Msg
import com.example.claude.data.Repeat
import com.example.claude.data.Repo
import com.example.claude.data.Schedule
import com.example.claude.data.Prompts
import com.example.claude.net.AI
import com.example.claude.net.ClaudeApi
import java.util.Calendar
import java.util.UUID

object Scheduler {
    const val CHANNEL = "claude_replies"
    const val EXTRA_ID = "schedule_id"
    const val EXTRA_CHAT = "chat_id"
    const val EXTRA_QUEUE = "queue_id"

    fun nextTrigger(s: Schedule, from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, s.hour); set(Calendar.MINUTE, s.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun ok() = c.timeInMillis > from && (s.repeat != Repeat.WEEKDAYS ||
            c.get(Calendar.DAY_OF_WEEK) !in listOf(Calendar.SATURDAY, Calendar.SUNDAY))
        while (!ok()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pending(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.hashCode(),
        Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context, s: Schedule) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, s.id))
        if (!s.enabled) return
        val at = nextTrigger(s)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, s.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, s.id))
    }

    private fun pendingQueued(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, ("q$id").hashCode(),
        Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_QUEUE, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun scheduleQueued(ctx: Context, q: com.example.claude.data.QueuedSend) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingQueued(ctx, q.id)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, q.at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, q.at, pi)
    }

    fun cancelQueued(ctx: Context, id: String) = ctx.getSystemService(AlarmManager::class.java).cancel(pendingQueued(ctx, id))

    fun runQueuedNow(ctx: Context, id: String) {
        val req = OneTimeWorkRequestBuilder<QueueWorker>()
            .setInputData(Data.Builder().putString(EXTRA_QUEUE, id).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    fun cancel(ctx: Context, id: String) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))

    fun rescheduleAll(ctx: Context) {
        Repo.init(ctx); Repo.schedules.value.forEach { schedule(ctx, it) }
        Repo.queue.value.forEach { if (it.at <= System.currentTimeMillis()) runQueuedNow(ctx, it.id) else scheduleQueued(ctx, it) }
    }

    fun runNow(ctx: Context, id: String) {
        val req = OneTimeWorkRequestBuilder<PromptWorker>()
            .setInputData(Data.Builder().putString(EXTRA_ID, id).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Clause replies", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun notify(ctx: Context, title: String, text: String, chatId: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, chatId.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_CHAT, chatId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
            .setContentIntent(open).setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(chatId.hashCode(), n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(ctx)
        intent.getStringExtra(Scheduler.EXTRA_QUEUE)?.let { Scheduler.runQueuedNow(ctx, it); return }
        val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
        val s = Repo.schedule(id) ?: return
        if (!s.enabled) return
        Scheduler.runNow(ctx, id)
        if (s.repeat == Repeat.ONCE) Repo.upsertSchedule(s.copy(enabled = false, lastRun = System.currentTimeMillis()))
        else { Repo.upsertSchedule(s.copy(lastRun = System.currentTimeMillis())); Scheduler.schedule(ctx, s) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Scheduler.rescheduleAll(ctx) }
}

class PromptWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Scheduler.ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Scheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Claude is working on your scheduled prompt…").build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1001, n)
    }

    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        val s = inputData.getString(Scheduler.EXTRA_ID)?.let(Repo::schedule) ?: return Result.failure()
        val key = Repo.apiKey.value ?: return Result.failure()
        val chatId = UUID.randomUUID().toString()
        val prompt = Msg("user", s.prompt)
        return try {
            val (reply, sources) = AI.completeFull(key, Repo.model.value, Prompts.system(null), listOf(prompt), web = s.webSearch)
            Repo.upsertChat(Chat(chatId, "⏰ ${s.name}", listOf(prompt, Msg("assistant", reply, sources = sources)), fromSchedule = true))
            Scheduler.notify(applicationContext, s.name, reply, chatId)
            Result.success()
        } catch (e: Exception) {
            Repo.upsertChat(Chat(chatId, "⏰ ${s.name}", listOf(prompt, Msg("assistant", "⚠️ Scheduled run failed: ${e.message}")), fromSchedule = true))
            Scheduler.notify(applicationContext, s.name, "Scheduled run failed: ${e.message}", chatId)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

/** Re-sends a chat's last prompt after a usage/rate limit cleared, then notifies. Retries if still limited. */
class QueueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Scheduler.ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Scheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Claude is sending your queued prompt…").build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1002, n)
    }

    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        val ctx = applicationContext
        val qid = inputData.getString(Scheduler.EXTRA_QUEUE) ?: return Result.failure()
        val q = Repo.queued(qid) ?: return Result.success()
        val chat = Repo.chat(q.chatId)
        val key = Repo.apiKey.value
        if (chat == null || key == null || chat.msgs.lastOrNull()?.role != "user") { Repo.deleteQueued(qid); return Result.success() }
        return try {
            val (reply, sources) = AI.completeFull(key, Repo.model.value, Prompts.system(chat.projectId), chat.msgs, 4096, q.web)
            Repo.upsertChat(chat.copy(msgs = chat.msgs + Msg("assistant", reply, sources = sources), updated = System.currentTimeMillis()))
            Repo.deleteQueued(qid)
            Scheduler.notify(ctx, chat.title, reply, chat.id)
            Result.success()
        } catch (e: Exception) {
            val limited = (e as? com.example.claude.net.ApiException)?.isLimit == true
            if ((limited || e !is com.example.claude.net.ApiException) && q.attempts < 6) {
                val wait = ((e as? com.example.claude.net.ApiException)?.retryAfterSec ?: if (limited) 1800L else 300L).coerceAtLeast(30)
                val next = q.copy(at = System.currentTimeMillis() + wait * 1000, attempts = q.attempts + 1)
                Repo.upsertQueued(next); Scheduler.scheduleQueued(ctx, next)
                Result.success()
            } else {
                Repo.deleteQueued(qid)
                Scheduler.notify(ctx, chat.title, "Queued prompt failed: ${e.message}", chat.id)
                Result.failure()
            }
        }
    }
}
