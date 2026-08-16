package com.deepseekharness.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Shizuku shell 执行封装：通过 UserService 在 root/shell 身份下执行设备命令，
 * 让助手（deepseek-harness agent）无需 root 即可操作设备。
 */
object ShizukuShell {

    @Volatile
    private var shellService: IShellService? = null
    @Volatile
    private var binding = false

    /** Shizuku 服务是否可用 */
    @JvmStatic
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /** 是否已获得 Shizuku 权限 */
    @JvmStatic
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /** 请求 Shizuku 权限（结果通过 listener 回调） */
    @JvmStatic
    fun requestPermission(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(9527)
        } catch (ignored: Throwable) {
        }
    }

    /** 绑定 UserService（进程由 Shizuku 以 root/shell 身份托管） */
    @JvmStatic
    fun ensureBound(ctx: Context) {
        if (binding || shellService != null) return
        if (!hasPermission()) return
        binding = true
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(ctx, ShellService::class.java))
                .daemon(false)
                .version(1)
            Shizuku.bindUserService(args, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    shellService = IShellService.Stub.asInterface(binder)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    shellService = null
                    binding = false
                }
            })
        } catch (ignored: Throwable) {
            binding = false
        }
    }

    /** 通过 UserService 执行 shell 命令并返回输出 */
    @JvmStatic
    fun exec(cmd: String): String {
        if (!hasPermission()) {
            return "[NO_SHIZUKU_PERMISSION]"
        }
        val s = shellService
        if (s == null) {
            return "[SHIZUKU_SERVICE_NOT_READY]"
        }
        return try {
            s.exec(cmd)
        } catch (e: Throwable) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
