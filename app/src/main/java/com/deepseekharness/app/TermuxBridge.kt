package com.deepseekharness.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Termux 桥接：通过 RUN_COMMAND Intent 在 Termux 内执行命令。
 * 普通 App 的 SELinux 上下文不允许 proot 所需的 ptrace，因此一体式 proot 方案
 * 在非 root 环境下不可行；改由 Termux（具备正确 SELinux 上下文）承载 Linux 环境。
 */
object TermuxBridge {

    const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    /** Termux 是否已安装 */
    @JvmStatic
    fun isInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** 引导安装 Termux（F-Droid 优先，回退 GitHub） */
    @JvmStatic
    fun openInstall(ctx: Context) {
        val urls = arrayOf(
            "https://f-droid.org/repo/com.termux_118.apk",
            "https://github.com/termux/termux-app/releases/latest"
        )
        for (url in urls) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            } catch (ignored: Exception) {
            }
        }
    }

    /** 打开 Termux 主界面 */
    @JvmStatic
    fun openApp(ctx: Context) {
        try {
            val i = ctx.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * 在 Termux 内后台执行一段 bash 脚本。
     * @param script  要执行的脚本内容（作为 bash -c 的参数）
     * @param workdir Termux 内的工作目录（可为 null）
     */
    @JvmStatic
    fun runScript(ctx: Context, script: String, workdir: String?) {
        val intent = Intent()
        intent.component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        intent.action = ACTION_RUN_COMMAND
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir ?: TERMUX_HOME)
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", 1)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            throw IllegalStateException("无法启动 Termux 命令服务: ${e.message}")
        }
    }
}
