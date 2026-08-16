package com.deepseekharness.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 任务完成通知器：监控 rootfs 内「会话文件」（session.jsonl.zstd）的写入活动。
 * 判定规则（降低误报）：
 *  - 只统计会话文件变化（排除 settings.yaml 等非对话写入）
 *  - 连续 3 次轮询（约 12 秒）都有写入才判定"agent 干活中"
 *  - 干活中连续静默 90 秒 = 任务完成 → 发通知
 * App 在前台（用户正在看预览）时不打扰。
 */
class TaskNotifier(private val ctx: Context, private val c: HarnessController) {

    private var executor: ScheduledExecutorService? = null
    private var lastActive = 0L
    private var activeStreak = 0
    private var armed = false

    fun start() {
        if (executor != null) return
        createChannel()
        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleWithFixedDelay({ tick() }, 5, POLL_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
        armed = false
        activeStreak = 0
        lastActive = 0
    }

    private fun tick() {
        try {
            if (!c.isWebRunning() || appInForeground) return
            // 只检测会话文件（agent 回复/思考记录），排除配置等非对话写入
            val out = c.proot.execAndRead(
                "find /root/.dsh -type f -name 'session*' -newermt '-" +
                    (POLL_MS / 1000 + 1) + " seconds' 2>/dev/null | head -3"
            )
            val active = out != null && out.trim().isNotEmpty()
            val now = System.currentTimeMillis()
            if (active) {
                lastActive = now
                if (activeStreak < ARM_STREAK) activeStreak++
                if (activeStreak >= ARM_STREAK) armed = true
            } else {
                activeStreak = 0
                if (armed && now - lastActive >= IDLE_MS) {
                    armed = false
                    notifyDone()
                }
            }
        } catch (ignored: Exception) {
            // 进程重启/网络抖动期间静默跳过
        }
    }

    private fun notifyDone() {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launch)
            .setContentTitle("DeepSeek Harness · 任务完成")
            .setContentText("智能体已结束任务，点击查看结果")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        nm?.notify(NOTIF_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "任务完成提醒", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "智能体任务完成时通知"
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            nm?.createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_ID = "dsh_task_channel"
        private const val NOTIF_ID = 2002
        private const val POLL_MS = 4000L
        private const val IDLE_MS = 90000L     // 静默 90 秒判定完成（容忍 agent 长思考）
        private const val ARM_STREAK = 3       // 连续 3 次活跃（约 12 秒）才武装

        /** App 是否在前台（MainActivity 维护）；前台时不发通知 */
        @JvmStatic
        @Volatile
        var appInForeground = false
    }
}
