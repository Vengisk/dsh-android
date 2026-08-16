package com.deepseekharness.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileOutputStream

/** 工作区管理模块：工作目录配置、环境信息、无 ROOT 文件共享（MT 注入文件提供器） */
class WorkspaceFragment : Fragment() {

    private var c: HarnessController? = null
    private val pickBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) restoreBackup(uri)
        }
    private lateinit var workdirEdit: EditText
    private lateinit var infoText: TextView
    private lateinit var shareStatusText: TextView
    private lateinit var shizukuStatusText: TextView
    private lateinit var prefs: android.content.SharedPreferences

    @Nullable
    override fun onCreateView(@NonNull inflater: LayoutInflater, @Nullable container: ViewGroup?,
                              @Nullable savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_workspace, container, false)
    }

    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        c = HarnessController.get(requireContext())
        prefs = requireContext().getSharedPreferences("deepseekharness", 0)
        workdirEdit = view.findViewById(R.id.workspace_path)
        infoText = view.findViewById(R.id.workspace_info)
        shareStatusText = view.findViewById(R.id.workspace_share_status)
        shizukuStatusText = view.findViewById(R.id.workspace_shizuku_status)
        val applyBtn: Button = view.findViewById(R.id.workspace_apply)
        val shizukuAuthBtn: Button = view.findViewById(R.id.workspace_shizuku_auth)
        val clearBtn: Button = view.findViewById(R.id.workspace_clear)
        val backupBtn: Button = view.findViewById(R.id.workspace_backup)
        val restoreBtn: Button = view.findViewById(R.id.workspace_restore)
        val resetBtn: Button = view.findViewById(R.id.workspace_reset)

        workdirEdit.setText(c?.getWorkdir())
        refreshInfo()

        applyBtn.setOnClickListener {
            val wd = workdirEdit.text.toString().trim()
            if (wd.isNotEmpty()) {
                c?.setWorkdir(wd)
                refreshInfo()
                Toast.makeText(requireContext(), "工作区已更新", Toast.LENGTH_SHORT).show()
            }
        }

        shizukuAuthBtn.setOnClickListener {
            if (!ShizukuShell.isAvailable()) {
                Toast.makeText(requireContext(), "请先安装并启动 Shizuku", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ShizukuShell.requestPermission { _, _ -> refreshShizukuStatus() }
            refreshShizukuStatus()
        }

        clearBtn.setOnClickListener {
            c?.proot?.uninstall()
            refreshInfo()
            Toast.makeText(requireContext(), "已清除环境", Toast.LENGTH_SHORT).show()
        }

        backupBtn.setOnClickListener {
            Toast.makeText(requireContext(), "正在备份，请稍候…", Toast.LENGTH_SHORT).show()
            Thread {
                val path = BackupManager.backupToExternal(requireContext(), c!!)
                if (activity == null) return@Thread
                activity!!.runOnUiThread {
                    if (path == null) {
                        Toast.makeText(requireContext(), "备份失败：环境可能未安装", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle("备份完成")
                        .setMessage("已导出到：\n$path")
                        .setPositiveButton("复制路径") { _, _ ->
                            val cm = requireContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("backup", path))
                                Toast.makeText(requireContext(), "路径已复制", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("好", null)
                        .show()
                }
            }.start()
        }

        resetBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("重置配置？")
                .setMessage("将删除 settings.yaml 和 .env（对话记录保留），并重新写入 .env。")
                .setPositiveButton("重置") { _, _ ->
                    val r = c?.resetConfig()
                    Toast.makeText(requireContext(), r, Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        restoreBtn.setOnClickListener {
            pickBackup.launch(arrayOf("*/*"))
        }
    }

    private fun restoreBackup(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle("恢复备份？")
            .setMessage("将用备份文件覆盖当前的配置和对话记录。\n建议先停止 Web UI 再恢复。")
            .setPositiveButton("恢复") { _, _ -> doRestore(uri) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doRestore(uri: Uri) {
        Toast.makeText(requireContext(), "正在恢复，请稍候…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val tmp = File(c!!.proot.rootfsDirFile, "root/.dsh-restore.tar.gz")
                if (tmp.parentFile != null) tmp.parentFile!!.mkdirs()
                requireContext().contentResolver.openInputStream(uri)?.use { inp ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
                // 解压到 /root（备份包内含 .dsh、<wd>/.env、dsh-web.log）
                c!!.proot.execChecked("cd /root && tar -xzf .dsh-restore.tar.gz 2>/dev/null; "
                    + "test -d .dsh && echo OK || echo EMPTY")
                tmp.delete()
                // 同步 API key：恢复的 .env 写回 App 配置，避免下次启动被覆盖
                val env = c!!.proot.execAndRead(
                    "cat /root/" + c!!.getWorkdir() + "/.env 2>/dev/null")
                if (env != null) {
                    for (line in env.split("\n")) {
                        if (line.startsWith("DEEPSEEK_API_KEY=")) {
                            val key = line.substring("DEEPSEEK_API_KEY=".length).trim()
                            if (key.isNotEmpty()) c!!.setApiKey(key)
                            break
                        }
                    }
                }
                if (activity == null) return@Thread
                activity!!.runOnUiThread {
                    Toast.makeText(requireContext(), "恢复完成（API key 已同步）",
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (activity != null) {
                    activity!!.runOnUiThread {
                        Toast.makeText(requireContext(),
                            "恢复失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (c != null) refreshInfo()
    }

    private fun refreshInfo() {
        val envState = if (c!!.isHarnessInstalled()) "✅ 已安装"
        else if (c!!.proot.isInstalled()) "🔄 环境已就绪" else "📦 未安装"
        infoText.text = "环境状态：$envState\n\n工作区（rootfs 内）：/root/" + c!!.getWorkdir() +
            "\n\n安装完成后该目录即为 deepseek-harness 源码。"
        refreshShareStatus()
    }

    private fun refreshShareStatus() {
        shareStatusText.text = "文件提供器已就绪（MT 官方注入，无需 ROOT）\n\n" +
            "用法：MT 管理器 → 侧拉栏 → 添加本地存储 → 选择「DeepSeek Harness」\n\n" +
            "工作区在：data → files → linux → ubuntu → root → " + c!!.getWorkdir() + "\n" +
            "配置在：data → files → linux → ubuntu → root → .dsh\n\n" +
            "（若 MT 里看不到内容，先打开本 App 保持进程运行）"
        refreshShizukuStatus()
    }

    private fun refreshShizukuStatus() {
        if (!::shizukuStatusText.isInitialized) return
        if (!ShizukuShell.isAvailable()) {
            shizukuStatusText.text = "Shizuku 未安装或未启动\n（装好 Shizuku 后，在这里授权）"
        } else if (ShizukuShell.hasPermission()) {
            shizukuStatusText.text = "✅ Shizuku 已授权，助手可执行设备 shell 命令"
        } else {
            shizukuStatusText.text = "Shizuku 已就绪，点击「授权 Shizuku」"
        }
    }
}
