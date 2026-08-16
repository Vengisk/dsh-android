package com.deepseekharness.app

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局崩溃兜底：把未捕获异常的完整堆栈写到外部存储 Download/dsh-android/crash.log，
 * 并把文件路径写入 SharedPreferences，下次启动可一键查看/复制。
 */
object CrashCatcher {

    private const val TAG = "CrashCatcher"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "dsh-android"
                )
                dir.mkdirs()
                val logFile = File(dir, "crash.log")
                val sw = StringWriter()
                sw.write("=== ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} ===\n")
                sw.write("thread=${thread.name}\n")
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.flush()
                logFile.writeText(sw.toString())
                app.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .edit().putString("crash_log", logFile.absolutePath).apply()
                Log.e(TAG, "crash logged to ${logFile.absolutePath}", throwable)
            } catch (e: Exception) {
                Log.e(TAG, "failed to write crash log", e)
            }
            // 继续默认行为（进程崩溃退出），不吞掉异常
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }
}