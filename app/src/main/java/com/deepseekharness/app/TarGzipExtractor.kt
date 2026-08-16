package com.deepseekharness.app

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * 纯 Java 流式 tar.gz 解压器，不依赖系统 tar（Android toybox tar 对 Ubuntu rootfs
 * 的符号链接、pax 长文件名、权限位支持差，会导致解压失败或闪退）。
 *
 * 支持：普通文件、目录、符号链接、硬链接、GNU/pax 长文件名；特殊文件安全跳过。
 */
object TarGzipExtractor {

    private const val BLOCK = 512

    @JvmStatic
    @Throws(IOException::class)
    fun extract(tarball: File, dest: File) {
        extract(tarball, dest, 0)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun extract(tarball: File, dest: File, strip: Int) {
        FileInputStream(tarball).use { raw ->
            GZIPInputStream(BufferedInputStream(raw, 1 shl 16)).use { gz ->
                val header = ByteArray(BLOCK)
                val buf = ByteArray(8192)
                var pendingName: String? = null

                while (true) {
                    if (!readFull(gz, header, BLOCK)) break          // EOF
                    if (isZeroBlock(header)) {
                        if (!readFull(gz, header, BLOCK)) break      // 结束块
                        if (isZeroBlock(header)) break               // 双零块 = 结束
                        continue                                     // 单零块 = padding，继续
                    }

                    var name = parseString(header, 0, 100)
                    val size = parseOctal(header, 124, 12)
                    val mode = parseOctal(header, 100, 8).toInt()
                    val type = header[156].toInt() and 0xFF
                    val linkname = parseString(header, 157, 100)

                    // 长文件名头（GNU 'L' / pax 'x'）
                    if (type == 'L'.code || type == 'x'.code) {
                        val longData = ByteArray(clampSize(size))
                        readFull(gz, longData, longData.size)
                        skipPadding(gz, size)
                        pendingName = if (type == 'L'.code)
                            parseString(longData, 0, longData.size)
                        else
                            parsePaxPath(longData)
                        continue
                    }

                    if (pendingName != null) {
                        name = pendingName
                        pendingName = null
                    }

                    // ustar prefix
                    val prefix = parseString(header, 345, 155)
                    if (!prefix.isNullOrEmpty()) {
                        name = prefix + "/" + name
                    }

                    // 去掉前 strip 层目录（用于去掉 tarball 的顶层目录）
                    if (strip > 0) {
                        var n = name
                        var stripped = n
                        for (i in 0 until strip) {
                            val idx = stripped.indexOf('/')
                            if (idx < 0) {
                                stripped = ""
                                break
                            }
                            stripped = stripped.substring(idx + 1)
                        }
                        if (stripped.isEmpty()) {
                            skipPadding(gz, size)
                            continue
                        }
                        n = stripped
                        name = n
                    }

                    val out = File(dest, name)

                    // 防御：条目名非法 = 预构建包损坏（下载被墙/截断的假包），直接报错
                    // 而不是用乱码路径创建文件（否则会抛 FileNotFoundException: <乱码路径>）
                    if (name.isEmpty() || name.startsWith("/")
                        || name.contains("\"") || name.contains(",")
                        || name.contains("..") || name.contains("\u0000")
                    ) {
                        throw IOException("预构建包损坏（非法文件条目: ${safeName(name)}），请重新下载或改用「直连源码构建」")
                    }

                    when (type) {
                        '0'.code, 0, '7'.code -> writeFile(gz, out, size, mode, buf)
                        '5'.code -> {
                            out.mkdirs()
                            skipPadding(gz, size)
                        }
                        '2'.code -> {
                            out.parentFile?.mkdirs()
                            try {
                                Os.symlink(linkname, out.absolutePath)
                            } catch (ignored: Throwable) {
                            }
                            skipPadding(gz, size)
                        }
                        '1'.code -> {
                            out.parentFile?.mkdirs()
                            try {
                                Os.link(File(dest, linkname).absolutePath, out.absolutePath)
                            } catch (ignored: Throwable) {
                            }
                            skipPadding(gz, size)
                        }
                        else -> {
                            // 设备节点等特殊文件：安全跳过
                            skipPadding(gz, size)
                        }
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun writeFile(input: InputStream, out: File, size: Long, mode: Int, buf: ByteArray) {
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { fos ->
            var remaining = size
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("tar 数据意外结束")
                fos.write(buf, 0, n)
                remaining -= n
            }
        }
        chmodBestEffort(out, mode)
        skipPadding(input, size)
    }

    private fun chmodBestEffort(f: File, mode: Int) {
        try {
            Os.chmod(f.absolutePath, mode and 0x1FF)
        } catch (ignored: Throwable) {
        }
    }

    @Throws(IOException::class)
    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (BLOCK - (size % BLOCK)) % BLOCK
        var remaining = pad
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun safeName(name: String): String {
        val s = name.replace("\n", "\\n").replace("\r", "\\r")
        return if (s.length > 60) s.substring(0, 60) + "…" else s
    }

    @Throws(IOException::class)
    private fun readFull(input: InputStream, b: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = input.read(b, off, len - off)
            if (n < 0) return off == len
            off += n
        }
        return true
    }

    private fun isZeroBlock(b: ByteArray): Boolean {
        for (x in b) if (x.toInt() != 0) return false
        return true
    }

    private fun parseString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, StandardCharsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = b[i]
            if (c.toInt() == 0 || c == ' '.toByte()) continue
            if (c < '0'.toByte() || c > '7'.toByte()) break
            v = v * 8 + (c - '0'.toByte()).toLong()
        }
        return v
    }

    private fun parsePaxPath(data: ByteArray): String? {
        val s = String(data, StandardCharsets.UTF_8)
        for (line in s.split("\n")) {
            if (line.startsWith("path=")) return line.substring(5)
        }
        return null
    }

    private fun clampSize(size: Long): Int = minOf(size, 64L * 1024).toInt()
}
