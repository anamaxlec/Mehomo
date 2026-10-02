package dev.memoh.android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.memoh.android.MainActivity
import dev.memoh.android.R
import java.time.Instant

class TaskNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val posted = mutableSetOf<String>()
    init {
        manager.createNotificationChannels(listOf(
            NotificationChannel(MONITOR, "后台监控", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(TASKS, "任务状态", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(DECISIONS, "等待审批与回答", NotificationManager.IMPORTANCE_HIGH)))
    }
    val allowed: Boolean get() = manager.areNotificationsEnabled()

    private fun open(account: String? = null, bot: String? = null, session: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (account != null && bot != null && session != null) {
            intent.data = Uri.Builder().scheme("memoh-task").authority("chat").appendPath(account).appendPath(bot).appendPath(session).build()
            intent.putExtra("account_id", account).putExtra("bot_id", bot).putExtra("session_id", session)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun monitor(description: String): Notification {
        val stop = PendingIntent.getService(context, 0, Intent(context, TaskMonitorService::class.java).setAction(TaskMonitorService.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, MONITOR)
            .setSmallIcon(R.drawable.ic_task_notification).setContentTitle("Mehomo 后台监控")
            .setContentText(description).setContentIntent(open()).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setShowWhen(false)
            .addAction(0, "停止监控", stop).build()
    }

    fun task(account: String, bot: String, session: String, title: String, task: TrackedTask,
        alert: Boolean, promoted: Boolean) {
        if (!allowed) return
        val tag = Uri.Builder().scheme("memoh-task").authority("notification").appendPath(account).appendPath(bot).appendPath(session).build().toString()
        val channel = if (task.decisionId != null) DECISIONS else TASKS
        val requestPromotion = promoted && !task.terminal && NotificationManagerCompat.from(context).canPostPromotedNotifications()
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_task_notification).setContentTitle(title.take(80))
            .setContentText(task.phase).setStyle(NotificationCompat.BigTextStyle().bigText(task.phase))
            .setContentIntent(open(account, bot, session)).setOngoing(!task.terminal).setAutoCancel(task.terminal)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setOnlyAlertOnce(!alert).setSilent(!alert)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_task_notification)
                .setContentTitle("Mehomo 任务").setContentText(task.phase).build())
            .setRequestPromotedOngoing(requestPromotion)
            .setShortCriticalText(if (!task.terminal) task.phase.take(7) else null)
        val started = task.startedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        if (requestPromotion) {
            val unpin = Intent(context, TaskMonitorService::class.java).setAction(TaskMonitorService.UNPIN)
                .setData(Uri.Builder().scheme("memoh-task").authority("unpin").appendPath(task.runId).build())
                .putExtra("run_id", task.runId)
            builder.addAction(0, "取消置顶", PendingIntent.getService(context, 0, unpin,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (!task.terminal && started != null) builder.setWhen(started).setUsesChronometer(true)
        else builder.setShowWhen(false)
        manager.notify(tag, TASK_ID, builder.build())
        posted.add(tag)
    }

    fun cancelTask(account: String, bot: String, session: String) {
        val tag = Uri.Builder().scheme("memoh-task").authority("notification").appendPath(account).appendPath(bot).appendPath(session).build().toString()
        manager.cancel(tag, TASK_ID); posted.remove(tag)
    }
    fun clear() { posted.forEach { manager.cancel(it, TASK_ID) }; posted.clear() }

    companion object {
        const val MONITOR = "cloud-monitor"
        const val TASKS = "task-status"
        const val DECISIONS = "task-decisions"
        const val FOREGROUND_ID = 1
        private const val TASK_ID = 2
    }
}
