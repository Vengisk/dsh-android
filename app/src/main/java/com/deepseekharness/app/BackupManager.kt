package com.deepseekharness.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全量备份到外部存储（Download/dsh-android/）：
 * rootfs 内打包 .dsh（配置+对话记录）+ .env + 日志 → 拷贝到公共下载目录。
 * Android 10+ 走 MediaStore（无需权限）；Android 9- 直接写公共目录。
 */
object BackupManager {

    /** 执行备份并导出，返回外部存储中的完整路径；失败返回 null */
    @JvmStatic
    fun backupToExternal(ctx: Context, c: HarnessController): String? {
        try {
            // 1. rootfs 内打包
            val wd = c.getWorkdir()
            c.proot.execChecked(
                "cd /root && rm -f .dsh-backup.tar.gz && " +
                    "tar -czf .dsh-backup.tar.gz .dsh $wd/.env dsh-web.log 2>/dev/null; " +
                    "test -s .dsh-backup.tar.gz && echo OK || echo EMPTY"
            )
            val tmp = File(c.proot.rootfsDirFile, "root/.dsh-backup.tar.gz")
            if (!tmp.isFile || tmp.length() == 0L) return null

            val name = "dsh-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(Date()) + ".tar.gz"
            val path = if (Build.VERSION.SDK_INT >= 29) writeViaMediaStore(ctx, tmp, name)
            else writeDirect(tmp, name)
            tmp.delete()
            return path
        } catch (e: Exception) {
            return null
        }
    }

    /** Android 10+：MediaStore Downloads 集合，无需存储权限 */
    private fun writeViaMediaStore(ctx: Context, src: File, name: String): String? {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/gzip")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/dsh-android")
        val uri: Uri? = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) return null
        FileInputStream(src).use { inp ->
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                val buf = ByteArray(8192)
                var n: Int
                while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
            }
        }
        return Environment.getExternalStorageDirectory().absolutePath + "/Download/dsh-android/" + name
    }

    /** Android 9-：直接写公共下载目录（需要 WRITE_EXTERNAL_STORAGE 权限） */
    @Suppress("DEPRECATION")
    private fun writeDirect(src: File, name: String): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "dsh-android")
        if (!dir.exists() && !dir.mkdirs()) return null
        val dst = File(dir, name)
        FileInputStream(src).use { inp ->
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(8192)
                var n: Int
                while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
            }
        }
        return dst.absolutePath
    }
}
