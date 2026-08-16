package com.deepseekharness.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Shizuku UserService：在 root/shell（ADB）身份下执行 shell 命令。
 * 由 ShizukuShell 通过 bindUserService 绑定，进程由 Shizuku 托管。
 */
class ShellService : IShellService.Stub() {

    override fun exec(cmd: String?): String {
        try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val MAX = 256 * 1024
            p.inputStream.use { inp: InputStream ->
                var n: Int
                while (inp.read(buf).also { n = it } != -1) {
                    if (bos.size() < MAX) {
                        val w = minOf(n, MAX - bos.size())
                        bos.write(buf, 0, w)
                    }
                }
            }
            val code = p.waitFor()
            return bos.toString(StandardCharsets.UTF_8.name()) + "\n[EXIT=" + code + "]"
        } catch (e: Throwable) {
            return "ERROR: " + e.javaClass.simpleName + ": " + e.message
        }
    }
}