package com.deepseekharness.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HarnessController private constructor(private val ctx: Context) {

    fun interface StateListener {
        fun onStateChanged()
    }

    companion object {
        const val STEP_ROOTFS = 1
        const val STEP_TOOLS = 2
        const val STEP_NODE = 3
        const val STEP_HARNESS = 4

        const val TASK_ROOTFS = 1
        const val TASK_NODE = 2
        const val TASK_HARNESS = 3

        private const val PREFS = "deepseekharness"
        private const val GUARD_VERSION = "8"

        @Volatile
        private var instance: HarnessController? = null

        private val IO: ExecutorService = Executors.newSingleThreadExecutor()

        @Synchronized
        @JvmStatic
        fun get(ctx: Context): HarnessController {
            if (instance == null) {
                instance = HarnessController(ctx.applicationContext)
            }
            return instance!!
        }

        @JvmStatic
        fun stepName(step: Int): String = when (step) {
            STEP_ROOTFS -> "① Linux 环境（rootfs）"
            STEP_TOOLS -> "② 基础工具（apt）"
            STEP_NODE -> "③ Node.js"
            STEP_HARNESS -> "④ deepseek-harness"
            else -> "未知步骤"
        }

        private fun describe(e: Throwable): String {
            val sb = StringBuilder()
            sb.append(e.javaClass.simpleName).append(": ").append(e.message)
            val st = e.stackTrace
            if (st != null && st.isNotEmpty()) {
                sb.append("\n    at ").append(st[0].toString())
            }
            return sb.toString()
        }

        private fun hostOf(url: String): String {
            return try {
                val h = java.net.URI(url).host
                h ?: url
            } catch (e: Exception) {
                url
            }
        }

        private fun sourceLabel(url: String): String {
            val h = hostOf(url)
            return when {
                h.startsWith("cdn.npmmirror") -> "npmmirror CDN（$h）"
                h.contains("npmmirror") -> "npmmirror（$h）"
                h.contains("tuna") -> "清华镜像（$h）"
                h.contains("aliyun") -> "阿里云镜像（$h）"
                h.contains("huaweicloud") -> "华为云镜像（$h）"
                h.contains("tencent") -> "腾讯云镜像（$h）"
                h.contains("nju.edu") -> "南京大学镜像（$h）"
                h.contains("hit.edu") -> "哈工大镜像（$h）"
                h.contains("bfsu") -> "北外镜像（$h）"
                h.contains("sjtu") -> "上海交大镜像（$h）"
                h.contains("nodejs.org") -> "Node 官方（$h）"
                h.contains("cdimage") -> "Ubuntu 官方（$h）"
                h.contains("catbox") -> "catbox 网盘（$h）"
                else -> h
            }
        }
    }

    private val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val proot = ProotBootstrap(ctx)
    private val listeners = CopyOnWriteArrayList<StateListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var stage: String = ""
    @Volatile var percent: Int = 0
    @Volatile var message: String = ""
    @Volatile var error: String = ""
    @Volatile var busy: Boolean = false
    @Volatile var currentStep: Int = 0
    @Volatile private var webProcess: Process? = null

    @Volatile private var awaitingSource: Boolean = false
    @Volatile private var sourceChoice: Int = -1
    @Volatile private var pendingTask: Int = 0
    @Volatile private var pendingUrls: Array<String>? = null
    @Volatile private var pendingLat: LongArray? = null
    private val sourceLock = Object()

    fun addStateListener(l: StateListener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeStateListener(l: StateListener) { listeners.remove(l) }

    private fun setState(stage: String, percent: Int, message: String, error: String, busy: Boolean) {
        this.stage = stage
        this.percent = percent
        this.message = message
        this.error = error
        this.busy = busy
        val ed = prefs.edit()
        ed.putString("last_stage", stage)
        ed.putString("last_error", error)
        ed.apply()
        mainHandler.post { for (l in listeners) l.onStateChanged() }
    }

    private fun setProgress(msg: String, pct: Int) = setState(stage, pct, msg, "", busy)

    fun getLastStage(): String = prefs.getString("last_stage", "") ?: ""
    fun getLastError(): String = prefs.getString("last_error", "") ?: ""

    private fun errMsg(prefix: String, e: Throwable): String {
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e2: Exception) { "?" }
        return "$prefix\n${describe(e)}\n\nApp 版本 $ver"
    }

    // ---------- 配置读写 ----------
    fun getApiKey(): String = prefs.getString("api_key", "") ?: ""
    fun setApiKey(v: String) { prefs.edit().putString("api_key", v).apply() }

    fun getPort(): String = prefs.getString("port", "3080") ?: "3080"
    fun setPort(v: String) { prefs.edit().putString("port", v).apply() }

    fun getModel(): String = prefs.getString("model", "deepseek-chat") ?: "deepseek-chat"
    fun setModel(v: String) { prefs.edit().putString("model", v).apply() }

    fun getPermissionMode(): String = prefs.getString("permission_mode", "ask") ?: "ask"
    fun setPermissionMode(v: String) { prefs.edit().putString("permission_mode", v).apply() }

    fun getWorkdir(): String = prefs.getString("workdir", "dsh") ?: "dsh"
    fun setWorkdir(v: String) { prefs.edit().putString("workdir", v).apply() }

    fun effectiveApiKey(): String {
        var k = getApiKey().trim()
        if (k.isEmpty()) {
            k = System.getenv("DEEPSEEK_API_KEY") ?: ""
        }
        return k.trim()
    }

    fun isHarnessInstalled(): Boolean {
        val wd = getWorkdir()
        return File(proot.rootfsDirFile, "root/$wd/.dsh").exists() &&
               File(proot.rootfsDirFile, "root/$wd/.dsh/package.json").exists()
    }

    fun isWebRunning(): Boolean {
        val p = webProcess
        return p != null && p.isAlive
    }

    // ---------- 一键安装 ----------
    fun install() {
        if (busy) return
        setState("准备安装", 0, "开始一键安装 deepseek-harness", "", true)
        IO.execute {
            try {
                for (s in STEP_ROOTFS..STEP_HARNESS) {
                    if (isStepDone(s)) continue
                    runInstallStep(s)
                }
                setState("安装完成", 100, "deepseek-harness 安装完成", "", false)
            } catch (e: Exception) {
                setState("安装失败", percent, "", errMsg("安装失败", e), false)
            }
        }
    }

    fun installStep(step: Int) {
        if (busy) return
        IO.execute {
            try {
                runInstallStep(step)
                setState("步骤完成", percent, "${stepName(step)} 完成", "", false)
            } catch (e: Exception) {
                setState("步骤失败", percent, "", errMsg("${stepName(step)} 失败", e), false)
            }
        }
    }

    fun isStepDone(step: Int): Boolean {
        return when (step) {
            STEP_ROOTFS -> proot.isInstalled()
            STEP_TOOLS -> toolsInstalled()
            STEP_NODE -> File(proot.rootfsDirFile, "usr/local/bin/node").exists() &&
                         File(proot.rootfsDirFile, "usr/local/bin/npm").exists()
            STEP_HARNESS -> isHarnessInstalled() && hasPtyNode()
            else -> false
        }
    }

    private fun hasPtyNode(): Boolean {
        val wd = getWorkdir()
        val base = File(proot.rootfsDirFile, "root/$wd/node_modules/.pnpm")
        if (!base.isDirectory) return false
        val files = base.listFiles() ?: return false
        for (f in files) {
            if (!f.name.startsWith("node-pty@")) continue
            val r1 = File(f, "node_modules/node-pty/build/Release/pty.node")
            if (r1.exists()) return true
            val r2 = File(f, "node_modules/node-pty/prebuilds/linux-arm64/pty.node")
            if (r2.exists()) return true
        }
        return false
    }

    private fun validXz(f: File): Boolean {
        if (!f.exists() || f.length() < 8) return false
        return try {
            f.inputStream().use { s ->
                val magic = ByteArray(6)
                s.read(magic)
                magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
                magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() &&
                magic[4] == 0x5A.toByte() && magic[5] == 0x00.toByte()
            }
        } catch (e: Exception) { false }
    }

    private fun validGzip(f: File): Boolean {
        if (!f.exists() || f.length() < 8) return false
        return try {
            f.inputStream().use { s ->
                val magic = ByteArray(2)
                s.read(magic)
                magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()
            }
        } catch (e: Exception) { false }
    }

    private fun toolsInstalled(): Boolean {
        return try {
            val out = proot.execAndRead("command -v curl git python3 make gcc xz >/dev/null 2>&1 && echo TOOLS_OK") ?: ""
            out.trim() == "TOOLS_OK"
        } catch (e: Exception) { false }
    }

    private fun runInstallStep(step: Int) {
        currentStep = step
        try {
            when (step) {
                STEP_ROOTFS -> installRootfs()
                STEP_TOOLS -> installTools()
                STEP_NODE -> installNode()
                STEP_HARNESS -> installHarness()
            }
        } finally {
            currentStep = 0
        }
    }

    private fun requireRootfs() {
        if (!proot.isInstalled()) throw Exception("Linux 环境（rootfs）尚未安装，请先安装步骤①")
    }

    private fun requireTools() {
        if (!toolsInstalled()) throw Exception("基础工具尚未安装，请先安装步骤②")
    }

    private fun installRootfs() {
        requireTools()  // 本机宿主工具
        setProgress("准备下载 Ubuntu rootfs", 2)
        val parent = proot.rootfsDirFile.parentFile
        val tarball = File(parent, "rootfs.tar.xz")
        val done = File(parent, "rootfs.tar.xz.done")
        val haveComplete = done.exists() && tarball.exists() && tarball.length() > 15L * 1024 * 1024
        if (haveComplete) {
            setProgress("rootfs 已下载", 30)
        } else {
            downloadWithPick(TASK_ROOTFS, ProotBootstrap.ROOTFS_URLS, "下载 Ubuntu rootfs（~30MB）", tarball, 4, 2)
        }
        setProgress("解压 rootfs", 57)
        proot.extractRootfs(tarball)
        setProgress("配置网络", 58)
        proot.setupResolvConf()
        setProgress("环境自检", 59)
        val smoke = proot.smokeTest()
        if (!smoke.contains("SMOKE_OK")) {
            throw Exception("rootfs 环境自检未通过：$smoke\n\n${proot.diagnoseRootfs()}")
        }
        proot.markInstalled()
        tarball.delete()
        done.delete()
    }

    private fun installTools() {
        requireRootfs()
        setProgress("配置 apt 源", 60)
        runStep("更新 apt 源", 62,
            "sed -i 's|ports.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g; s|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list 2>/dev/null; " +
            "apt-get update -y")
        setProgress("安装基础工具", 65)
        runStep("安装基础工具", 65,
            "apt-get install -y --no-install-recommends curl git python3 make gcc g++ xz-utils ca-certificates 2>&1 | tail -5; " +
            "dpkg --configure -a 2>/dev/null || true")
        setProgress("基础工具就绪", 69)
    }

    private fun installNode() {
        requireRootfs()
        requireTools()
        val nodePkg = File(proot.rootfsDirFile, "tmp/node.tar.xz")
        setProgress("准备 Node.js v24", 71)
        var haveGood = nodePkg.exists() && nodePkg.length() >= 40L * 1024 * 1024 && validXz(nodePkg)
        if (haveGood) {
            setProgress("Node.js 安装包已存在，跳过下载", 71)
        } else {
            if (nodePkg.exists()) {
                nodePkg.delete()
            }
            downloadWithPick(TASK_NODE, ProotBootstrap.NODE_URLS, "下载 Node.js", nodePkg, 71, 6)
            haveGood = validXz(nodePkg)
        }
        if (!haveGood) {
            nodePkg.delete()
            throw Exception("Node.js 安装包下载后校验失败，请检查网络后重试")
        }
        setProgress("安装 Node.js", 88)
        runStep("安装 Node.js", 88,
            "cd /tmp && (tar -xJf node.tar.xz -C /usr/local --strip-components=1 || " +
            "(echo '安装包损坏，自动重新下载…'; rm -f node.tar.xz; " +
            "curl -kfsSL --retry 3 " +
            "https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o node.tar.xz && " +
            "tar -xJf node.tar.xz -C /usr/local --strip-components=1)) && " +
            "node -v && npm -v")
        nodePkg.delete()
        setProgress("Node.js 安装完成", 89)
    }

    private fun installHarness() {
        requireRootfs()
        requireTools()
        val urls = ProotBootstrap.HARNESS_URLS
        val lat = proot.probeAll(urls, 6000)
        setProgress("测速完成，等待选择源", percent)
        val ordered = waitUserPick(TASK_HARNESS, urls, lat)
        if (ordered.isNotEmpty() && ordered[0].startsWith("git://")) {
            installHarnessFromSource()
            return
        }
        setProgress("下载 deepseek-harness", 91)
        val parent = proot.rootfsDirFile.parentFile
        val pkg = File(parent, "dsh.tar.gz")
        val pkgDone = File(parent, "dsh.tar.gz.done")
        var ok = false
        var lastErr = "未知错误"
        if (pkgDone.exists() && pkg.exists() && pkg.length() > 1024 * 1024 && validGzip(pkg)) {
            ok = true
        } else {
            for (u in ordered) {
                try {
                    proot.downloadRootfs(u, pkg) { p ->
                        if (p < 0) setProgress("下载 deepseek-harness（源未提供大小）…", 92)
                        else setProgress("下载 deepseek-harness（$p%）", Math.min(99, 92 + p / 12))
                    }
                    if (!validGzip(pkg)) {
                        pkg.delete()
                        throw Exception("下载完成但 gzip 校验失败")
                    }
                    ok = true
                    break
                } catch (e: Exception) {
                    lastErr = e.message ?: "未知错误"
                    pkg.delete()
                }
            }
        }
        if (!ok) throw Exception("deepseek-harness 下载失败：$lastErr\n\n可尝试：切换网络 / 开启代理")
        if (!pkgDone.exists()) { pkgDone.createNewFile() }
        setProgress("解压 deepseek-harness", 97)
        proot.extractHarness(pkg, File(proot.rootfsDirFile, "root/${getWorkdir()}"))
        val apiKey = effectiveApiKey()
        if (apiKey.isNotEmpty()) {
            setProgress("写入 API key", 99)
            runStep("写入 API key", 99,
                "cd /root/${getWorkdir()} && printf 'DEEPSEEK_API_KEY=%s\\n' '" + apiKey + "' > .env")
        }
        pkg.delete()
        pkgDone.delete()
    }

    private fun installHarnessFromSource() {
        requireRootfs()
        setProgress("从源码构建 deepseek-harness", 70)
        proot.setupResolvConf()
        if (!toolsInstalled()) {
            installTools()
        }
        // 启用 pnpm
        runStep("启用 pnpm", 75,
            "(command -v pnpm >/dev/null 2>&1 && pnpm -v) || " +
            "npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com 2>&1 | tail -3")
        // 获取源码（多通道 fallback）
        setProgress("获取源码", 78)
        val wd = getWorkdir()
        val cloneOk = runCatching {
            val channels = arrayOf(
                "https://github.com/fanchangyong/deepseek-harness.git",
                "https://ghproxy.net/https://github.com/fanchangyong/deepseek-harness.git",
                "https://mirror.ghproxy.com/https://github.com/fanchangyong/deepseek-harness.git",
                "https://ghfast.top/https://github.com/fanchangyong/deepseek-harness.git",
                "https://hub.gitmirror.com/https://github.com/fanchangyong/deepseek-harness.git"
            )
            var last = ""
            for (u in channels) {
                try {
                    runStep("clone $wd（$u）", 78,
                        "rm -rf /root/$wd /tmp/dsh-src.tar.gz; " +
                        "git clone --depth 1 --branch main $u /root/$wd 2>&1 | tail -2 && " +
                        "test -f /root/$wd/package.json")
                    return@runCatching true
                } catch (e: Exception) { last = e.message ?: "" }
            }
            // codeload 源码包 fallback
            try {
                runStep("下载源码包（codeload）", 78,
                    "cd /root && curl -kfsSL --retry 3 -o dsh-src.tar.gz " +
                    "https://codeload.github.com/fanchangyong/deepseek-harness/tar.gz/refs/heads/main && " +
                    "mkdir -p $wd && tar -xzf dsh-src.tar.gz --strip-components=1 -C $wd && " +
                    "test -f $wd/package.json && rm -f dsh-src.tar.gz")
                true
            } catch (e: Exception) { false }
        }.getOrDefault(false)
        if (!cloneOk) throw Exception("获取源码失败，请检查网络或手动安装")

        setProgress("应用补丁", 82)
        applyPatches(wd)
        // 安装 rootfs-confirm-install.sh（失败必须中断）
        runStep("安装确认脚本", 83,
            "cd /root/$wd && test -f scripts/rootfs-confirm-install.sh && " +
            "bash scripts/rootfs-confirm-install.sh 2>&1 | tail -3 || " +
            "(echo '缺少 rootfs-confirm-install.sh，安装中断'; exit 1)")
        // 准备 Node headers
        runStep("准备 Node headers", 84,
            "mkdir -p /root/.cache/node-gyp/24.19.0/include/node && " +
            "(test -f /root/.cache/node-gyp/24.19.0/include/node/node.h && echo NODE_H_OK) || " +
            "(curl -kfsSL --retry 3 -o /tmp/nh.tar.gz " +
            "https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-headers.tar.gz && " +
            "tar -xzf /tmp/nh.tar.gz -C /root/.cache/node-gyp/24.19.0 --strip-components=1 && " +
            "rm -f /tmp/nh.tar.gz && test -f /root/.cache/node-gyp/24.19.0/include/node/node.h && echo NODE_H_OK)")
        // 追加 onlyBuiltDependencies 白名单
        runStep("配置构建白名单", 85,
            "cd /root/$wd && " +
            "grep -q 'onlyBuiltDependencies' pnpm-workspace.yaml 2>/dev/null || " +
            "printf '\\nonlyBuiltDependencies:\\n  - node-pty\\n' >> pnpm-workspace.yaml; " +
            "(command -v python >/dev/null 2>&1 || ln -sf /usr/bin/python3 /usr/bin/python)")
        // pnpm install
        setProgress("安装依赖（耗时较长）", 86)
        runStep("安装依赖", 86,
            "cd /root/$wd && printf 'registry=https://registry.npmmirror.com\\n' > /root/.npmrc && " +
            "pnpm install --no-frozen-lockfile 2>&1 | tail -8")
        // 编译 node-pty
        setProgress("编译 node-pty", 92)
        runStep("编译 node-pty", 92,
            "cd /root/$wd && " +
            "NP=$(ls -d node_modules/.pnpm/node-pty@*/node_modules/node-pty 2>/dev/null | head -1) && " +
            "if [ -f \"\$NP/prebuilds/linux-arm64/pty.node\" ]; then " +
            "echo '检测到 node-pty 预编译产物，跳过编译'; " +
            "else cd \"\$NP\" && " +
            "GYP=\$(find /root/.cache -maxdepth 6 -name node-gyp.js 2>/dev/null | head -1); " +
            "test -n \"\$GYP\" || GYP=\$(find /usr/local/lib/node_modules -name node-gyp.js 2>/dev/null | head -1); " +
            "node \"\$GYP\" rebuild > /tmp/node-gyp.log 2>&1 || " +
            "{ echo '--- node-gyp 编译失败 ---'; tail -30 /tmp/node-gyp.log; exit 1; }; fi")
        // 验证 pty.node
        runStep("验证 pty.node", 94,
            "cd /root/$wd && " +
            "P=\$(ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/Release/pty.node 2>/dev/null | head -1); " +
            "test -n \"\$P\" || P=\$(ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/prebuilds/linux-arm64/pty.node 2>/dev/null | head -1); " +
            "test -n \"\$P\" && echo \"pty.node OK: \$P\" || " +
            "(echo 'pty.node 未生成，node-gyp 日志：'; tail -40 /tmp/node-gyp.log; exit 1)")
        // 构建
        setProgress("构建 webui", 96)
        runStep("构建 webui", 96, "cd /root/$wd && pnpm run build 2>&1 | tail -10")
        // 写 .env
        val apiKey = effectiveApiKey()
        if (apiKey.isNotEmpty()) {
            setProgress("写入 API key", 99)
            runStep("写入 API key", 99,
                "cd /root/$wd && printf 'DEEPSEEK_API_KEY=%s\\n' '" + apiKey + "' > .env")
        }
        setProgress("deepseek-harness 构建完成", 99)
    }

    private fun applyPatches(wd: String) {
        // webui-sidebar.patch
        runCatching {
            runStep("应用 webui 补丁", 82,
                "cd /root/$wd && (test -f scripts/webui-sidebar.patch && " +
                "patch -p1 --forward < scripts/webui-sidebar.patch 2>&1 | tail -2 || echo '补丁跳过')")
        }
        // bash-guard.patch
        runCatching {
            runStep("应用 bash-guard 补丁", 82,
                "cd /root/$wd && (test -f scripts/bash-guard.patch && " +
                "patch -p1 --forward < scripts/bash-guard.patch 2>&1 | tail -2 || echo '补丁跳过')")
        }
        // webui-polyfill.sh
        runCatching {
            runStep("应用 webui polyfill", 82,
                "cd /root/$wd && (test -f scripts/webui-polyfill.sh && " +
                "bash scripts/webui-polyfill.sh 2>&1 | tail -2 || echo 'polyfill 跳过')")
        }
    }

    // ---------- 源选择 ----------
    private fun downloadWithPick(task: Int, urls: Array<String>, what: String, dest: File, pBase: Int, pDiv: Int) {
        val lat = proot.probeAll(urls, 6000)
        val ordered = waitUserPick(task, urls, lat)
        var lastErr = "未知错误"
        var ok = false
        for (u in ordered) {
            try {
                proot.downloadRootfs(u, dest) { p ->
                    if (p < 0) setProgress("$what（源未提供大小）…", pBase)
                    else setProgress("$what（$p%）", Math.min(99, pBase + p / pDiv))
                }
                ok = true
                break
            } catch (e: Exception) {
                lastErr = e.message ?: "未知错误"
                dest.delete()
            }
        }
        if (!ok) throw Exception("$what 下载失败：$lastErr\n\n可尝试：切换网络 / 开启代理")
    }

    private fun waitUserPick(task: Int, urls: Array<String>, lat: LongArray): Array<String> {
        pendingTask = task
        pendingUrls = urls
        pendingLat = lat
        sourceChoice = -1
        awaitingSource = true
        setState("请选择下载源（测速完成）", percent, "", "", true)
        synchronized(sourceLock) {
            val deadline = System.currentTimeMillis() + 120_000
            while (awaitingSource && System.currentTimeMillis() < deadline) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                try { sourceLock.wait(remain) } catch (e: InterruptedException) { break }
            }
        }
        awaitingSource = false
        val best = bestIndex(lat)
        var first = sourceChoice
        if (first < 0 || first >= urls.size) first = best
        val ordered = ArrayList<String>()
        ordered.add(urls[first])
        for (i in urls.indices) {
            if (i != first) ordered.add(urls[i])
        }
        prefs.edit().putInt("src_task", first).apply()
        pendingTask = 0
        pendingUrls = null
        pendingLat = null
        return ordered.toTypedArray()
    }

    private fun bestIndex(lat: LongArray): Int {
        var b = 0
        var best = Long.MAX_VALUE
        for (i in lat.indices) {
            if (lat[i] >= 0 && lat[i] < best) { best = lat[i]; b = i }
        }
        return b
    }

    fun onSourceChosen(index: Int) {
        sourceChoice = index
        awaitingSource = false
        synchronized(sourceLock) { sourceLock.notifyAll() }
    }

    fun isAwaitingSourceChoice(): Boolean = awaitingSource

    fun getPendingSourceLabels(): Array<String> {
        val urls = pendingUrls ?: return arrayOf()
        val lat = pendingLat ?: return urls
        val out = ArrayList<String>(urls.size)
        for (i in urls.indices) {
            val u = urls[i]
            val l = if (i < lat.size) lat[i] else -1
            if (u.startsWith("git://")) {
                out.add("⚡ 直连 GitHub 源码构建（clone + 本地构建，无需预构建包）")
            } else {
                out.add(sourceLabel(u) + if (l >= 0) "   延迟 ${l}ms" else "   不可用 ✗")
            }
        }
        return out.toTypedArray()
    }

    fun getPendingDefaultIndex(): Int {
        val urls = pendingUrls ?: return 0
        val last = prefs.getInt("src_task", -1)
        if (last in urls.indices) return last
        val lat = pendingLat ?: return 0
        return bestIndex(lat)
    }

    // ---------- 步骤执行 ----------
    private fun runStep(stage: String, percent: Int, cmd: String) {
        setProgress(stage, percent)
        val fullCmd = "($cmd) >/root/dsh-step.log 2>&1" +
            " || { echo '--- 日志尾部 ---'; tail -100 /root/dsh-step.log; exit 1; }"
        proot.execChecked(fullCmd)
    }

    fun readAsset(name: String): String {
        return try {
            ctx.assets.open(name).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) { "" }
    }

    fun buildInstallScript(): String {
        val apiKey = effectiveApiKey()
        val port = getPort()
        val model = getModel()
        val perm = getPermissionMode()
        return readAsset("install.sh")
            .replace("@@API_KEY@@", apiKey)
            .replace("@@PORT@@", port)
            .replace("@@MODEL@@", model)
            .replace("@@PERMISSION_MODE@@", perm)
    }

    private fun startWebCommand(): String {
        val wd = getWorkdir()
        return "cd /root/$wd && " +
            "export DSH_HOME=/root/.dsh && " +
            "export DEEPSEEK_API_KEY=\"" + effectiveApiKey() + "\" && " +
            "export DSH_PERMISSION_MODE=\"" + getPermissionMode() + "\" && " +
            "export DSH_CONFIRM=1 && " +
            "export BASH_ENV=/root/dsh-guard.sh && " +
            "node apps/cli/lib/bin.js web > ~/dsh-web.log 2>&1"
    }

    private fun stopWebCommand(): String {
        return "pkill -f 'bin.js web' 2>/dev/null || true"
    }

    private fun statusCommand(): String {
        val port = getPort()
        return "curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:$port/ || echo 000"
    }

    // ---------- Web 启停 ----------
    fun startWeb() {
        val p = webProcess
        if (p != null && p.isAlive) {
            setState("Web UI", 0, "Web UI 已在运行", "", false)
            return
        }
        IO.execute {
            try {
                setProgress("正在启动 Web UI", 0)
                proot.ensureRuntimeFiles()
                ensureDangerGuard()
                ensureBashGuardPatch()
                val proc = proot.execRootfs(startWebCommand())
                webProcess = proc
                if (proc == null) throw Exception("启动进程失败（execRootfs 返回 null）")
                val out = proot.drainOutput(proc)
                if (out.isNullOrEmpty()) {
                    // 后台进程可能没有输出，检查日志尾部
                    val log = proot.execAndRead("tail -c 4000 ~/dsh-web.log 2>/dev/null") ?: ""
                    val tail = log.takeLast(500)
                    setState("Web UI 已退出", 0, "", "Web UI 启动后退出\n\n日志尾部：\n$tail", false)
                } else {
                    setState("Web UI 已退出", 0, "", "Web UI 启动后退出\n\n输出：\n" + out.takeLast(500), false)
                }
            } catch (e: Exception) {
                setState("启动失败", 0, "", errMsg("启动 Web UI 失败", e), false)
            }
        }
    }

    fun stopWeb() {
        IO.execute {
            try {
                val p = webProcess
                if (p != null) {
                    try { p.destroy() } catch (e: Exception) {}
                    webProcess = null
                }
                proot.execAndRead(stopWebCommand())
                setState("已停止", 0, "已停止后台服务", "", false)
            } catch (e: Exception) {
                setState("停止失败", 0, "", errMsg("停止失败", e), false)
            }
        }
    }

    fun checkStatus() {
        IO.execute {
            try {
                val out = proot.execAndRead(statusCommand()) ?: ""
                setState("状态检查", 0, "状态码：" + out.trim(), "", false)
            } catch (e: Exception) {
                setState("状态检查失败", 0, "", errMsg("状态检查失败", e), false)
            }
        }
    }

    // ---------- 危险操作防护 ----------
    /** 确保 rootfs 内危险命令确认包装器已部署（版本不匹配则强制重装，幂等） */
    fun ensureDangerGuard() {
        try {
            // 版本标记：旧版包装器/守卫不升级是之前漏拦截的根因，必须强制刷新
            val ver = proot.execAndRead("cat /root/dsh-bin/.version 2>/dev/null || echo 0")
            if (ver != null && ver.trim() == GUARD_VERSION) return
            val inst = readAsset("rootfs-confirm-install.sh")
            if (inst.isEmpty()) return
            // 清掉旧版（含旧 dsh-bin/守卫脚本），避免残留旧包装器
            proot.execChecked("rm -rf /root/dsh-bin /root/dsh-guard.sh /root/dsh-confirm.sh && echo CLEARED")
            val f = File(proot.rootfsDirFile, "root/install-confirm.sh")
            if (f.parentFile != null) f.parentFile.mkdirs()
            f.writeText(inst, StandardCharsets.UTF_8)
            proot.execChecked("bash /root/install-confirm.sh && rm -f /root/install-confirm.sh")
        } catch (e: Exception) {
            // 环境未安装等场景静默
        }
    }

    /** 给已构建的 bash 工具 lib 直接打补丁（强制每次执行前加载守卫，不依赖重新 build） */
    private fun ensureBashGuardPatch() {
        try {
            proot.execAndRead(
                "cd /root/" + getWorkdir() + " && "
                + "F=\$(ls packages/shell/bash-local/lib/index.js 2>/dev/null | head -1); "
                + "if [ -z \"\$F\" ]; then echo LIB_MISSING; "
                + "elif grep -q 'dsh-guard' \"\$F\"; then echo LIB_ALREADY; "
                + "else sed -i 's|command: request\\.command|command: `source /root/dsh-guard.sh 2>/dev/null; \${request.command}`|' \"\$F\" "
                + "&& grep -q 'dsh-guard' \"\$F\" && echo LIB_PATCHED || echo LIB_PATCH_FAIL; fi"
            )
        } catch (e: Exception) {
            // 忽略
        }
    }

    // ---------- Termux 模式 ----------
    fun isTermuxInstalled(): Boolean = TermuxBridge.isInstalled(ctx)

    fun openTermuxInstall() {
        TermuxBridge.openInstall(ctx)
    }

    private fun buildTermuxInstallScript(): String {
        val s = readAsset("install-termux.sh")
        return s.replace("@@API_KEY@@", effectiveApiKey())
            .replace("@@PERMISSION_MODE@@", getPermissionMode())
    }

    /** 通过 Termux 安装 deepseek-harness */
    fun installViaTermux() {
        setProgress("提交安装任务到 Termux", 5)
        try {
            TermuxBridge.runScript(ctx, buildTermuxInstallScript(), null)
            setState("", 30, "已提交到 Termux 执行，请切到 Termux 查看进度", "", false)
        } catch (e: Throwable) {
            setState("", 0, "", errMsg("提交失败：", e), false)
        }
    }

    private fun startWebTermuxCommand(): String {
        return "export PATH=\$HOME/dsh-bin:\$PATH && " +
            "cd ~/" + getWorkdir() + " && " +
            "export DEEPSEEK_API_KEY=\"" + effectiveApiKey() + "\" && " +
            "export DSH_PERMISSION_MODE=\"" + getPermissionMode() + "\" && " +
            "nohup node apps/cli/lib/bin.js web > ~/dsh-web.log 2>&1 & echo started"
    }

    /** 通过 Termux 启动 Web UI */
    fun startWebViaTermux() {
        setProgress("正在启动 Web UI", 0)
        try {
            TermuxBridge.runScript(ctx, startWebTermuxCommand(), null)
            setState("", 100, "已提交启动，稍候在「启动」页打开预览", "", false)
        } catch (e: Throwable) {
            setState("", 0, "", errMsg("启动失败：", e), false)
        }
    }

    fun stopWebViaTermux() {
        try {
            TermuxBridge.runScript(ctx, "pkill -f 'bin.js web' 2>/dev/null; echo stopped", null)
        } catch (e: Throwable) {
            // 忽略
        }
    }

    // ---------- 配置备份 / 重置 ----------
    fun backupConfig(): String? {
        try {
            val wd = getWorkdir()
            val backupRoot = File(ctx.filesDir, "backup")
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = File(backupRoot, "config-$ts")
            dir.mkdirs()
            var n = 0
            val env = rootfsFile("root/$wd/.env")
            if (env.isFile) {
                copyFile(env, File(dir, "env-$wd.txt"))
                n++
            }
            val dsh = rootfsFile("root/.dsh")
            if (dsh.isDirectory) {
                copyDir(dsh, File(dir, "dsh"))
                n++
            }
            return if (n > 0) dir.absolutePath else null
        } catch (e: Exception) {
            return null
        }
    }

    fun resetConfig(): String {
        return try {
            var any = false
            val settings = rootfsFile("root/.dsh/settings.yaml")
            if (settings.isFile) {
                settings.delete()
                any = true
            }
            val env = rootfsFile("root/" + getWorkdir() + "/.env")
            if (env.isFile) {
                env.delete()
                any = true
            }
            writeEnvFile()
            if (any) "配置已重置，对话记录已保留\n（.env 已按当前配置重写）"
            else "没有可重置的配置（.env 已重写）"
        } catch (e: Exception) {
            errMsg("重置失败：", e)
        }
    }

    private fun writeEnvFile() {
        try {
            val wd = getWorkdir()
            val envFile = File(File(proot.rootfsDirFile, "root"), "$wd/.env")
            envFile.parentFile.mkdirs()
            envFile.writeText("DEEPSEEK_API_KEY=" + effectiveApiKey() + "\n")
        } catch (e: Exception) { }
    }

    private fun copyFile(src: File, dst: File) {
        src.inputStream().use { inp -> dst.outputStream().use { out -> inp.copyTo(out) } }
    }

    private fun copyDir(srcDir: File, dstDir: File) {
        if (!srcDir.isDirectory) return
        dstDir.mkdirs()
        srcDir.listFiles()?.forEach { f ->
            if (f.isDirectory) copyDir(f, File(dstDir, f.name))
            else copyFile(f, File(dstDir, f.name))
        }
    }

    private fun rootfsFile(rel: String): File = File(proot.rootfsDirFile, rel)
}
