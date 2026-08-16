package com.deepseekharness.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.fragment.app.Fragment
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 内置终端：直接挂到 proot 的持久 bash 会话上。
 * cd / export 等状态保持（真终端体验），无需 Termux。
 */
class TerminalFragment : Fragment() {

    private var c: HarnessController? = null
    private var inputEdit: EditText? = null
    private var outputText: TextView? = null
    private var scrollView: ScrollView? = null
    private var shell: Process? = null
    @Volatile private var running = false
    private val buffer = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Nullable
    override fun onCreateView(@NonNull inflater: LayoutInflater, @Nullable container: ViewGroup?,
                              @Nullable savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_terminal, container, false)
    }

    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        c = HarnessController.get(requireContext())
        inputEdit = view.findViewById(R.id.term_input)
        outputText = view.findViewById(R.id.term_output)
        scrollView = view.findViewById(R.id.term_scroll)

        val ctrlcBtn: TextView = view.findViewById(R.id.term_ctrlc)
        ctrlcBtn.setOnClickListener {
            val p = shell
            if (p != null && p.isAlive) {
                try {
                    p.outputStream.write(3) // Ctrl+C
                    p.outputStream.flush()
                } catch (ignored: IOException) {
                }
            }
        }
        inputEdit?.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND
                || actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_DONE
                || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                sendCommand()
                true
            } else {
                false
            }
        }

        appendLine("Ubuntu 24.04 · 回车执行 · 中止 · exit 退出")
        if (c?.isHarnessInstalled() != true) {
            appendLine("环境未安装，请先到「安装」页完成安装")
            return
        }
        startShell()
    }

    private fun startShell() {
        c?.ensureDangerGuard() // 危险确认包装器缺失则自动补装（装新 APK 后无需重装第 4 步）
        Thread(Runnable {
            try {
                shell = c!!.proot.execRootfsInteractive()
                running = true
                val buf = ByteArray(8192)
                val inp: InputStream = shell!!.inputStream
                while (running) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    val chunk = stripAnsi(String(buf, 0, n, StandardCharsets.UTF_8))
                    mainHandler.post { appendRaw(chunk) }
                }
                mainHandler.post { appendLine("\n[会话已退出]") }
            } catch (e: Exception) {
                mainHandler.post { appendLine("终端启动失败：" + e.message) }
            }
        }, "term-read").start()
    }

    private fun sendCommand() {
        val cmd = inputEdit?.text.toString().trim()
        if (cmd.isEmpty()) return
        inputEdit?.setText("")
        appendLine("$ $cmd")
        val p = shell
        if (p == null || !p.isAlive) {
            appendLine("会话未运行，正在重启…")
            startShell()
            return
        }
        try {
            p.outputStream.write((cmd + "\n").toByteArray(StandardCharsets.UTF_8))
            p.outputStream.flush()
        } catch (e: IOException) {
            appendLine("发送失败：" + e.message)
        }
    }

    private fun appendLine(s: String) {
        appendRaw(s + "\n")
    }

    private fun appendRaw(s: String) {
        if (outputText == null) return
        if (buffer.length > 300000) buffer.setLength(0)
        buffer.append(s)
        val show = if (buffer.length > 100000) {
            "…（输出过长已截断）\n" + buffer.substring(buffer.length - 100000)
        } else {
            buffer.toString()
        }
        outputText?.text = show
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    /** 去掉 ANSI 转义序列（保留可读文本） */
    private fun stripAnsi(s: String): String {
        return s.replace(Regex("\\x1B\\[[0-9;?]*[a-zA-Z]"), "")
            .replace(Regex("\\x1B\\][^\\x07]*\\x07"), "")
            .replace(Regex("\\x1B[()][0-9A-B]"), "")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        running = false
        val p = shell
        if (p != null) {
            try {
                p.destroy()
            } catch (ignored: Exception) {
            }
            shell = null
        }
    }
}
