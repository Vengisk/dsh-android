package com.deepseekharness.app

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ProotBootstrap — 一体式 Linux 环境管理（PRoot 方案）。
 *
 * 关键设计（参考 openclaw-termux）：
 * proot、loader、libtalloc 伪装成 lib*.so 放入 jniLibs，Android 安装时
 * 自动解压到 nativeLibraryDir（可执行目录，绕过 App 私有目录的 noexec）。
 * 运行时通过 PROOT_LOADER / PROOT_TMP_DIR / LD_LIBRARY_PATH 环境变量
 * 引导 proot 找到 loader 与依赖库，直接 exec nativeLibraryDir/libproot.so。
 */
class ProotBootstrap(c: Context) {

    private val ctx: Context = c.applicationContext
    private val baseDir = File(ctx.filesDir, "linux")
    private val rootfsDir = File(baseDir, "ubuntu")
    private val libDir = File(baseDir, "lib")
    private val tmpDir = File(baseDir, "tmp")
    private val nativeLibDir: String = ctx.applicationInfo.nativeLibraryDir
    private val markerFile = File(baseDir, ".installed")

    val rootfsDirFile: File get() = rootfsDir

    fun isInstalled(): Boolean {
        return markerFile.exists() && File(rootfsDir, "bin").exists()
    }

    fun isHarnessInstalled(workdir: String): Boolean {
        return File(rootfsDir, "root/$workdir/lib/bin.js").exists() ||
            File(rootfsDir, "root/$workdir/apps/cli/lib/bin.js").exists()
    }

    /** 定位 native 库：nativeLibraryDir 优先，找不到则扫描 lib 根目录下各 ABI 子目录 */
    private fun findNativeLib(name: String): File {
        val direct = File(nativeLibDir, name)
        if (direct.isFile) return direct
        val libRoot = File(nativeLibDir).parentFile
        if (libRoot != null && libRoot.isDirectory) {
            val subs = libRoot.listFiles()
            if (subs != null) {
                for (sub in subs) {
                    if (sub.isDirectory) {
                        val f = File(sub, name)
                        if (f.isFile) return f
                    }
                }
            }
        }
        return direct
    }

    private fun prootPath(): String {
        return findNativeLib("libproot.so").absolutePath
    }

    private fun chmod(f: File, mode: Int) {
        f.setReadable(true, false)
        f.setExecutable(true, false)
        try {
            android.system.Os.chmod(f.absolutePath, mode)
        } catch (ignored: Throwable) {
        }
    }

    private fun writeFile(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        try {
            FileOutputStream(dest).use { out -> out.write(bytes) }
        } catch (ignored: IOException) {
        }
    }

    private fun copyExec(src: File, dst: File) {
        if (src.isFile && !dst.exists()) {
            try {
                FileInputStream(src).use { inp ->
                    FileOutputStream(dst).use { out ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
            } catch (ignored: IOException) {
            }
            chmod(dst, 0x1FD) // 0755
        }
    }

    /** 准备运行时：复制依赖库（匹配 SONAME）、创建目录 */
    fun ensureRuntimeFiles() {
        baseDir.mkdirs()
        tmpDir.mkdirs()
        libDir.mkdirs()

        // libtalloc.so.2（proot 的 NEEDED），jniLibs 里叫 libtalloc.so
        copyExec(findNativeLib("libtalloc.so"), File(libDir, "libtalloc.so.2"))
        // libandroid-shmem.so（旧版 proot 的 NEEDED）
        copyExec(findNativeLib("libandroidshmem.so"), File(libDir, "libandroid-shmem.so"))
    }

    private fun readAsset(name: String): ByteArray? {
        return try {
            ctx.assets.open(name).use { inp ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var n: Int
                while (inp.read(buf).also { n = it } != -1) bos.write(buf, 0, n)
                bos.toByteArray()
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun baseEnv(pb: ProcessBuilder) {
        pb.environment().put("PROOT_TMP_DIR", tmpDir.absolutePath)
        pb.environment().put("PROOT_LOADER", findNativeLib("libprootloader.so").absolutePath)
        pb.environment().put("PROOT_LOADER_32", findNativeLib("libprootloader32.so").absolutePath)
        pb.environment().put(
            "LD_LIBRARY_PATH",
            libDir.absolutePath + ":" + findNativeLib("libproot.so").parent
        )
        pb.environment().put("HOME", "/root")
        // 关键：guest 的 PATH（否则继承 Android 的 /system/bin，找不到 tail/apt 等）
        // 前置 /root/dsh-bin：危险命令确认包装器（DSH_CONFIRM=1 时拦截）
        pb.environment().put("PATH", "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        // 关键：TMPDIR 必须指向 guest 的 /tmp（否则 mktemp 用 Android 的 cache 目录而失败）
        pb.environment().put("TMPDIR", "/tmp")
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive")
    }

    /** 在 rootfs 内执行 bash 命令 */
    @Throws(IOException::class)
    fun execRootfs(bashCommand: String): Process {
        val argv = arrayOf(
            prootPath(),
            "--link2symlink", "-L", "--kill-on-exit",
            "-0",
            "--rootfs=" + rootfsDir.absolutePath,
            "--cwd=/root",
            "-b", "/dev",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "/bin/bash", "-c", bashCommand
        )
        val pb = ProcessBuilder(*argv).redirectErrorStream(true)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        baseEnv(pb)
        return pb.start()
    }

    /** 启动交互式 bash 会话（持久进程，可读写 stdin/stdout；cd/export 状态保持，供内置终端使用） */
    @Throws(IOException::class)
    fun execRootfsInteractive(): Process {
        val argv = arrayOf(
            prootPath(),
            "--link2symlink", "-L", "--kill-on-exit",
            "-0",
            "--rootfs=" + rootfsDir.absolutePath,
            "--cwd=/root",
            "-b", "/dev",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "/bin/bash"
        )
        val pb = ProcessBuilder(*argv).redirectErrorStream(true)
        baseEnv(pb)
        // 交互终端：危险命令启用确认（App 弹窗优先，交互输入兜底）
        pb.environment().put("DSH_CONFIRM", "1")
        pb.environment().put("DSH_INTERACTIVE", "1")
        return pb.start()
    }

    /** 同步执行 rootfs 命令并返回输出 */
    fun execAndRead(bashCommand: String): String {
        return try {
            val p = execRootfs(bashCommand)
            val out = readStream(p.inputStream)
            p.waitFor()
            out
        } catch (e: Throwable) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** 同步执行 rootfs 命令，退出码非 0 时抛异常 */
    @Throws(IOException::class)
    fun execChecked(bashCommand: String): String {
        val p = execRootfs(bashCommand)
        val out = readStream(p.inputStream)
        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("命令被中断", e)
        }
        if (code != 0) {
            val tail = if (out.length > 600) out.substring(out.length - 600) else out
            throw IOException("退出码 $code：\n$tail")
        }
        return out
    }

    /** 读取进程输出，最多保留 256KB 防止内存暴涨 */
    @Throws(IOException::class)
    private fun readStream(inp: InputStream): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        var kept = 0
        val max = 256 * 1024
        while (inp.read(buf).also { n = it } != -1) {
            if (kept < max) {
                val w = minOf(n, max - kept)
                bos.write(buf, 0, w)
                kept += w
            }
        }
        return bos.toString("UTF-8")
    }

    /** 阻塞读取进程输出，保持长驻进程存活；进程退出时返回最后一段输出 */
    @Throws(IOException::class)
    fun drainOutput(p: Process): String {
        val inp = p.inputStream
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        var kept = 0
        val max = 64 * 1024
        while (inp.read(buf).also { n = it } != -1) {
            if (kept < max) {
                val w = minOf(n, max - kept)
                bos.write(buf, 0, w)
                kept += w
            }
        }
        return bos.toString("UTF-8")
    }

    /** 冒烟测试：proot 能否直接 exec + 进 rootfs */
    fun smokeTest(): String {
        ensureRuntimeFiles()
        val diag = StringBuilder()
        diag.append("proot 路径: ").append(prootPath()).append("\n")
        diag.append("nativeLibDir: ").append(nativeLibDir).append("\n")
        try {
            val pb = ProcessBuilder(prootPath(), "--version").redirectErrorStream(true)
            pb.environment().put(
                "LD_LIBRARY_PATH",
                libDir.absolutePath + ":" + findNativeLib("libproot.so").parent
            )
            val p = pb.start()
            val v = readStream(p.inputStream)
            p.waitFor()
            diag.append("[1] proot --version: ").append(v?.trim()?.split("\n")?.firstOrNull() ?: "").append("\n")
        } catch (e: Throwable) {
            return "PROOT_FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
        val out = execAndRead("/bin/echo SMOKE_OK")
        diag.append("[2] rootfs exec: ").append(out?.trim() ?: "").append("\n")
        return diag.toString()
    }

    /** HEAD 请求测下载源延迟；可用返回耗时毫秒，失败返回 -1 */
    fun probeLatency(url: String, timeoutMs: Int): Long {
        val start = System.currentTimeMillis()
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", "dsh-android/1.0.0")
            val code = conn.responseCode
            conn.disconnect()
            if (code == 200 || code == 206) System.currentTimeMillis() - start else -1
        } catch (e: Throwable) {
            -1
        }
    }

    /** 并行测速全部源，返回延迟毫秒数组（-1 表示不可用） */
    fun probeAll(urls: Array<String>, timeoutMs: Int): LongArray {
        val lat = LongArray(urls.size)
        val latch = CountDownLatch(urls.size)
        val pool: ExecutorService = Executors.newFixedThreadPool(minOf(8, maxOf(1, urls.size)))
        for (i in urls.indices) {
            val idx = i
            pool.execute {
                try {
                    lat[idx] = probeLatency(urls[idx], timeoutMs)
                } finally {
                    latch.countDown()
                }
            }
        }
        try {
            latch.await(timeoutMs + 3000L, TimeUnit.MILLISECONDS)
        } catch (ignored: InterruptedException) {
        }
        pool.shutdownNow()
        return lat
    }

    /** 多源测速排序（并行）：延迟短的在前，测速失败（-1）排最后（仍作 fallback） */
    fun orderBySpeed(urls: Array<String>): Array<String> {
        val t = probeAll(urls, 6000)
        val out = urls.clone()
        for (i in 0 until out.size - 1) {
            for (j in i + 1 until out.size) {
                if (t[j] >= 0 && (t[i] < 0 || t[j] < t[i])) {
                    val su = out[i]; out[i] = out[j]; out[j] = su
                    val st = t[i]; t[i] = t[j]; t[j] = st
                }
            }
        }
        return out
    }

    /** 下载 rootfs（带进度回调，支持断点续传；完成后写 .done 标记） */
    @Throws(IOException::class)
    fun downloadRootfs(url: String, dest: File, progress: ((Int) -> Unit)?) {
        val existing = if (dest.exists()) dest.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.connectTimeout = 45000
        conn.readTimeout = 300000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "dsh-android/1.0.0")
        if (existing > 0) {
            conn.setRequestProperty("Range", "bytes=$existing-")
        }
        conn.connect()
        val code = conn.responseCode
        if (code != 200 && code != 206) throw IOException("HTTP $code")
        val resume = code == 206
        val contentLen = conn.contentLengthLong
        val totalBytes = if (resume && contentLen > 0) existing + contentLen else contentLen
        try {
            conn.inputStream.use { inp ->
                java.io.RandomAccessFile(dest, "rw").use { raf ->
                    if (resume) raf.seek(existing) else raf.setLength(0)
                    val buf = ByteArray(65536)
                    var downloaded = if (resume) existing else 0L
                    var n: Int
                    var lastPct = -1
                    while (inp.read(buf).also { n = it } != -1) {
                        raf.write(buf, 0, n)
                        downloaded += n
                        // 节流：仅百分比变化时回调（每 64KB 回调会把 UI 线程塞爆导致卡顿）
                        if (progress != null) {
                            if (totalBytes > 0) {
                                val pct = (downloaded * 100 / totalBytes).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    progress.invoke(pct)
                                }
                            } else if (lastPct != -2) {
                                lastPct = -2 // 源未提供大小：只通知一次"下载中"
                                progress.invoke(-1)
                            }
                        }
                    }
                    FileInputStream(dest).use { fis ->
                        val b0 = fis.read()
                        val b1 = fis.read()
                        // 按格式校验魔数：.xz 校验 xz 魔数（FD 37），其余按 gzip（1F 8B）
                        val xz = url.lowercase().contains(".xz") || dest.name.endsWith(".xz")
                        val okMagic = if (xz) (b0 == 0xfd && b1 == 0x37)
                        else (b0 == 0x1f && b1 == 0x8b)
                        if (!okMagic) {
                            dest.delete()
                            throw IOException("下载内容不是有效的压缩包（可能是错误页面），已清除")
                        }
                    }
                    try {
                        FileOutputStream(dest.absolutePath + ".done").use { fo ->
                            fo.write(downloaded.toString().toByteArray())
                        }
                    } catch (ignored: IOException) {
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 解压 rootfs（纯 Java 流式） */
    @Throws(IOException::class)
    fun extractRootfs(tarball: File) {
        if (rootfsDir.exists()) {
            deleteRecursively(rootfsDir)
        }
        rootfsDir.mkdirs()
        TarGzipExtractor.extract(tarball, rootfsDir)
        val hasBash = File(rootfsDir, "usr/bin/bash").exists() ||
            File(rootfsDir, "bin/bash").exists()
        if (!hasBash) {
            throw IOException("解压后 rootfs 不完整（缺少 bash），请清除环境后重试")
        }
    }

    /** 解压预构建包（去掉顶层目录）到 rootfs 的指定目录 */
    @Throws(IOException::class)
    fun extractHarness(tarball: File, target: File) {
        if (target.exists()) deleteRecursively(target)
        target.mkdirs()
        TarGzipExtractor.extract(tarball, target, 1)
    }

    fun setupResolvConf() {
        val rc = File(rootfsDir, "etc/resolv.conf")
        rc.parentFile?.mkdirs()
        if (rc.exists()) rc.delete()
        try {
            FileOutputStream(rc).use { o ->
                // 国内 DNS 优先保证基础解析（墙内 8.8.8.8/1.1.1.1 常被污染/不可达）
                o.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n".toByteArray())
            }
        } catch (ignored: IOException) {
        }
    }

    fun markInstalled() {
        markerFile.parentFile?.mkdirs()
        try {
            FileOutputStream(markerFile).use { o ->
                o.write(("installed=" + System.currentTimeMillis() + "\n").toByteArray())
            }
        } catch (ignored: IOException) {
        }
    }

    /** 诊断 rootfs 关键路径状态 */
    fun diagnoseRootfs(): String {
        val sb = StringBuilder()
        sb.append("rootfs 路径: ").append(rootfsDir.absolutePath).append("\n")
        val bash = File(rootfsDir, "usr/bin/bash")
        sb.append("usr/bin/bash 存在=").append(bash.exists())
            .append(if (bash.exists()) " 大小=" + bash.length() else "").append("\n")
        val ld = File(rootfsDir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        sb.append("ld-linux 存在=").append(ld.exists()).append("\n")
        val etc = File(rootfsDir, "etc/os-release")
        sb.append("etc/os-release 存在=").append(etc.exists()).append("\n")
        sb.append("已安装标记=").append(markerFile.exists())
        return sb.toString()
    }

    fun uninstall() {
        try {
            ProcessBuilder("/system/bin/rm", "-rf", baseDir.absolutePath)
                .redirectErrorStream(true).start().waitFor()
        } catch (e: Exception) {
            deleteRecursively(baseDir)
        }
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) {
            val children = f.listFiles()
            if (children != null) for (c in children) deleteRecursively(c)
        }
        f.delete()
    }

    companion object {
        @JvmField
        val ROOTFS_URLS = arrayOf(
            // 多镜像源（安装时并行测速，弹窗让你自选；全部实测可用）
            "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.hit.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
        )

        /** Node.js arm64 镜像（多源，并行测速 + 自选；全部实测可用） */
        @JvmField
        val NODE_URLS = arrayOf(
            "https://mirrors.huaweicloud.com/nodejs/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirrors.aliyun.com/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://cdn.npmmirror.com/binaries/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirror.nju.edu.cn/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirrors.cloud.tencent.com/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirror.sjtu.edu.cn/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz"
        )

        /** deepseek-harness 安装源：预构建包 + 直连 GitHub 源码构建（特殊项 git://） */
        @JvmField
        val HARNESS_URLS = arrayOf(
            "https://litter.catbox.moe/epfi6g.gz",
            // 特殊项：不下载预构建包，直接从 GitHub 克隆源码本地构建
            "git://github.com/deepseek-ai/deepseek-harness"
        )
    }
}
