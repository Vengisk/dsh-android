package com.deepseekharness.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务：强后台保活 Web UI。
 *  - startForeground 常驻通知，降低被系统回收概率；
 *  - START_STICKY 被杀后由系统重启；
 *  - 建议引导用户加入电池优化白名单。
 */
class HarnessService : Service() {

    private var c: HarnessController? = null
    private var shellHttp: HttpShellService? = null
    private var taskNotifier: TaskNotifier? = null
    private val stateListener = HarnessController.StateListener { refreshNotification() }

    override fun onCreate() {
        super.onCreate()
        val controller = HarnessController.get(this)
        c = controller
        createChannel()
        controller.addStateListener(stateListener)
        startForeground(NOTIF_ID, buildNotification("DeepSeek Harness 运行中", "Web UI 正在后台保持运行"))
        // 桥接 Shizuku shell 能力（rootfs 里的助手可通过 127.0.0.1:3090 执行设备命令）
        shellHttp = HttpShellService(this).also { it.start() }
        ShizukuShell.ensureBound(this)
        // 任务完成通知：agent 干活结束后提醒
        taskNotifier = TaskNotifier(this, controller).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_STOP == intent.action) {
            c?.stopWeb()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        c?.startWeb()
        return START_STICKY
    }

    private fun refreshNotification() {
        val controller = c ?: return
        if (controller.error.isNullOrEmpty().not()) {
            updateNotification("DeepSeek Harness 启动失败", controller.error)
        } else if (controller.message.isNullOrEmpty().not()) {
            updateNotification("DeepSeek Harness 运行中", "Web UI: http://127.0.0.1:" + controller.getPort())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================= 通知 =================
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "DeepSeek Harness 后台服务", NotificationManager.IMPORTANCE_LOW)
            ch.description = "保持 DeepSeek Harness Web UI 后台运行"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            nm?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = Intent(this, HarnessService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launch)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, "停止", stopPi)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        nm?.notify(NOTIF_ID, buildNotification(title, text))
    }

    override fun onDestroy() {
        c?.removeStateListener(stateListener)
        shellHttp?.stop()
        taskNotifier?.stop()
        c?.stopWeb()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.deepseekharness.app.START"
        const val ACTION_STOP = "com.deepseekharness.app.STOP"

        private const val CHANNEL_ID = "dsh_harness_channel"
        private const val NOTIF_ID = 1001
    }
}
