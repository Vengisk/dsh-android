package com.deepseekharness.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 极简 HTTP 服务（host 侧，端口 3090），把 Shizuku shell 能力桥接给 rootfs 里的助手。
 * rootfs 内的 agent 可用 bash 工具执行：
 *   curl -s "http://127.0.0.1:3090/exec?cmd=<urlencoded>"
 * 返回 JSON：{"result":"...输出...[EXIT=0]"}
 *
 * 安全：命中危险命令（删除/格式化/卸载/重启等）时，若设置开启"需确认"，
 * 前台弹窗 / 后台高优先级通知（允许/拒绝按钮），60 秒超时默认拒绝。
 */
class HttpShellService(private val ctx: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var pendingLatch: CountDownLatch? = null
    @Volatile
    private var pendingAllow = false
    /** 确认进行中标志：并发确认请求直接拒绝（避免 latch 覆盖导致"点了允许却拒绝"） */
    @Volatile
    private var confirmBusy = false

    private var server: ServerSocket? = null
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        instance = this
        val t = Thread({
            try {
                server = ServerSocket(PORT)
                while (running) {
                    try {
                        val client = server!!.accept()
                        handle(client)
                    } catch (e: IOException) {
                        if (!running) break
                    }
                }
            } catch (ignored: IOException) {
            }
        }, "http-shell")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running = false
        instance = null
        try {
            server?.close()
        } catch (ignored: IOException) {
        }
        // 释放挂起的确认（默认拒绝）
        pendingLatch?.countDown()
        cancelConfirmNotification()
    }

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                val path = if (parts.size > 1) parts[1] else "/"
                var cmd = ""
                if (path.startsWith("/exec") || path.startsWith("/confirm")) {
                    val q = path.indexOf("cmd=")
                    if (q >= 0) {
                        cmd = URLDecoder.decode(path.substring(q + 4), "UTF-8")
                    }
                }
                val result = when {
                    cmd.isEmpty() -> "[NO_CMD]"
                    path.startsWith("/confirm") -> {
                        // rootfs 内包装器请求的确认：只弹窗，不执行
                        if (confirmEnabled() && DangerShellGuard.isDangerous(cmd))
                            if (requestUserConfirm(cmd)) "YES" else "NO"
                        else "YES"
                    }
                    DangerShellGuard.isDangerous(cmd) && confirmEnabled() -> awaitConfirm(cmd)
                    else -> ShizukuShell.exec(cmd)
                }
                val body = "{\"result\":" + jsonEscape(result) + "}"
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: " + bodyBytes.size + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                c.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                c.getOutputStream().write(bodyBytes)
                c.getOutputStream().flush()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun confirmEnabled(): Boolean {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
            .getBoolean("confirm_shell", true)
    }

    /** 危险命令：挂起等待用户确认（前台弹窗 / 后台通知），超时默认拒绝 */
    private fun awaitConfirm(cmd: String): String {
        return if (requestUserConfirm(cmd)) ShizukuShell.exec(cmd) else "[USER_REJECTED]"
    }

    /** 只请求用户确认（不执行命令），返回是否允许；/confirm 端点用 */
    private fun requestUserConfirm(cmd: String): Boolean {
        if (confirmBusy) return false // 已有确认在进行：拒绝新的（避免状态覆盖）
        confirmBusy = true
        try {
            val latch = CountDownLatch(1)
            pendingLatch = latch
            pendingAllow = false

            val act = MainActivity.current
            if (act != null) {
                // 前台：App 内弹窗
                val prompt = "模型试图在设备上执行：\n$cmd\n\n是否允许？"
                act.runOnUiThread {
                    AlertDialog.Builder(act)
                        .setTitle("DeepSeek Harness 安全确认")
                        .setMessage(prompt)
                        .setPositiveButton("允许") { _, _ ->
                            pendingAllow = true
                            pendingLatch?.countDown()
                        }
                        .setNegativeButton("拒绝") { _, _ ->
                            pendingLatch?.countDown()
                        }
                        .setOnCancelListener {
                            pendingLatch?.countDown()
                        }
                        .setOnDismissListener {
                            pendingLatch?.countDown()
                        }
                        .show()
                }
            } else {
                // 后台：高优先级通知 + 允许/拒绝按钮
                showConfirmNotification(cmd)
            }

            return try {
                val finished = latch.await(CONFIRM_TIMEOUT_S, TimeUnit.SECONDS)
                pendingLatch = null
                if (!finished) {
                    cancelConfirmNotification()
                    false
                } else {
                    pendingAllow
                }
            } catch (e: InterruptedException) {
                pendingLatch = null
                false
            }
        } finally {
            confirmBusy = false
            pendingLatch = null
            cancelConfirmNotification()
        }
    }

    /** 通知按钮回调（ConfirmReceiver） */
    fun resolveConfirm(allow: Boolean) {
        pendingAllow = allow
        pendingLatch?.countDown()
        cancelConfirmNotification()
    }

    private fun showConfirmNotification(cmd: String) {
        createConfirmChannel()
        val shortCmd = if (cmd.length > 100) cmd.substring(0, 100) + "…" else cmd
        val allowI = Intent(ctx, ConfirmReceiver::class.java).setAction(ConfirmReceiver.ACTION_ALLOW)
        val denyI = Intent(ctx, ConfirmReceiver::class.java).setAction(ConfirmReceiver.ACTION_DENY)
        val allowPi = PendingIntent.getBroadcast(
            ctx, 31, allowI,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPi = PendingIntent.getBroadcast(
            ctx, 32, denyI,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CONFIRM_CHANNEL)
            .setSmallIcon(R.drawable.ic_launch)
            .setContentTitle("⚠️ DeepSeek Harness 安全确认")
            .setContentText("模型试图执行：" + shortCmd)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("模型试图在设备上执行：\n$cmd\n\n是否允许？")
            )
            .addAction(0, "允许", allowPi)
            .addAction(0, "拒绝", denyPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        nm?.notify(CONFIRM_NOTIF_ID, n)
    }

    private fun cancelConfirmNotification() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        nm?.cancel(CONFIRM_NOTIF_ID)
    }

    private fun createConfirmChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CONFIRM_CHANNEL, "安全确认", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "模型执行危险操作时的确认提醒"
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            nm?.createNotificationChannel(ch)
        }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                else sb.append(ch)
            }
        }
        return sb.toString()
    }

    companion object {
        const val PORT = 3090
        private const val CONFIRM_CHANNEL = "dsh_confirm_channel"
        private const val CONFIRM_NOTIF_ID = 3003
        private const val CONFIRM_TIMEOUT_S = 60L

        @Volatile
        var instance: HttpShellService? = null
    }
}
