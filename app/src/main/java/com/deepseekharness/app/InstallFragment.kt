package com.deepseekharness.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/** 安装模块：分步安装（rootfs / 基础工具 / Node / harness），多源测速，一键补装 */
class InstallFragment : Fragment() {

    private var c: HarnessController? = null
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var errorText: TextView
    private lateinit var stepStatusText: TextView
    private lateinit var installBtn: Button
    private lateinit var uninstallBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var step1Btn: Button
    private lateinit var step2Btn: Button
    private lateinit var step3Btn: Button
    private lateinit var step4Btn: Button
    private lateinit var progressBar: ProgressBar
    private var sourceDialog: AlertDialog? = null

    private val stateListener = HarnessController.StateListener { refreshFromState() }

    @Nullable
    override fun onCreateView(@NonNull inflater: LayoutInflater, @Nullable container: ViewGroup?,
                              @Nullable savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_install, container, false)
    }

    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        c = HarnessController.get(requireContext())
        statusText = view.findViewById(R.id.install_status)
        progressText = view.findViewById(R.id.install_progress)
        errorText = view.findViewById(R.id.install_error)
        stepStatusText = view.findViewById(R.id.install_steps)
        installBtn = view.findViewById(R.id.install_btn)
        uninstallBtn = view.findViewById(R.id.install_uninstall)
        copyBtn = view.findViewById(R.id.install_copy)
        progressBar = view.findViewById(R.id.install_progressbar)
        step1Btn = view.findViewById(R.id.install_step1)
        step2Btn = view.findViewById(R.id.install_step2)
        step3Btn = view.findViewById(R.id.install_step3)
        step4Btn = view.findViewById(R.id.install_step4)

        c?.addStateListener(stateListener)

        installBtn.setOnClickListener {
            if (c?.getApiKey()?.isEmpty() != false) {
                Toast.makeText(requireContext(), "请先在「配置」模块填入 API key", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            c?.install()
        }

        step1Btn.setOnClickListener { c?.installStep(HarnessController.STEP_ROOTFS) }
        step2Btn.setOnClickListener { c?.installStep(HarnessController.STEP_TOOLS) }
        step3Btn.setOnClickListener { c?.installStep(HarnessController.STEP_NODE) }
        step4Btn.setOnClickListener { c?.installStep(HarnessController.STEP_HARNESS) }

        uninstallBtn.setOnClickListener {
            c?.proot?.uninstall()
            Toast.makeText(requireContext(), "已清除环境", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        copyBtn.setOnClickListener {
            val err = c?.error
            if (err.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "当前没有报错内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dsh_error", err))
            Toast.makeText(requireContext(), "报错内容已复制", Toast.LENGTH_SHORT).show()
        }

        refreshFromState()
    }

    override fun onResume() {
        super.onResume()
        if (c != null) refreshStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        c?.removeStateListener(stateListener)
    }

    private fun refreshFromState() {
        if (!isAdded) return
        val cc = c ?: return
        val err = cc.error
        if (err.isNotEmpty()) {
            errorText.visibility = View.VISIBLE
            copyBtn.visibility = View.VISIBLE
            errorText.text = err
            progressBar.visibility = View.GONE
            progressText.visibility = View.GONE
        } else {
            errorText.visibility = View.GONE
            copyBtn.visibility = View.GONE
            if (cc.busy) {
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                val p = cc.percent.coerceIn(0, 100)
                progressBar.progress = p   // 关键：进度条要真正跟着百分比动
                progressText.text = cc.stage + " " + p + "%"
            } else {
                progressBar.visibility = View.GONE
                val msg = cc.message
                if (msg.isNotEmpty()) {
                    progressText.visibility = View.VISIBLE
                    progressText.text = msg
                } else {
                    progressText.visibility = View.GONE
                }
            }
        }
        val running = cc.busy
        installBtn.isEnabled = !running
        step1Btn.isEnabled = !running
        step2Btn.isEnabled = !running
        step3Btn.isEnabled = !running
        step4Btn.isEnabled = !running
        uninstallBtn.isEnabled = !running
        refreshSteps()
        refreshStatus()
        if (cc.isAwaitingSourceChoice()) showSourceDialog()
    }

    /** 测速完成：弹窗让用户自选下载源 */
    private fun showSourceDialog() {
        if (sourceDialog != null && sourceDialog!!.isShowing) return
        val cc = c ?: return
        val labels = cc.getPendingSourceLabels()
        if (labels.isEmpty()) return
        val defaultIdx = maxOf(0, cc.getPendingDefaultIndex())
        val sel = intArrayOf(defaultIdx)
        sourceDialog = AlertDialog.Builder(requireContext())
            .setTitle("选择下载源（已测速）")
            .setSingleChoiceItems(labels, defaultIdx) { _, which -> sel[0] = which }
            .setPositiveButton("就用这个源") { _, _ ->
                cc.onSourceChosen(sel[0])
                sourceDialog = null
            }
            .setNegativeButton("自动选最快") { _, _ ->
                cc.onSourceChosen(-1)
                sourceDialog = null
            }
            .setOnCancelListener {
                cc.onSourceChosen(-1)
                sourceDialog = null
            }
            .show()
    }

    /** 更新 4 个步骤的状态显示 */
    private fun refreshSteps() {
        step1Btn.text = stepLabel(HarnessController.STEP_ROOTFS)
        step2Btn.text = stepLabel(HarnessController.STEP_TOOLS)
        step3Btn.text = stepLabel(HarnessController.STEP_NODE)
        step4Btn.text = stepLabel(HarnessController.STEP_HARNESS)
        stepStatusText.text =
            "① Linux 环境（rootfs）   " + mark(HarnessController.STEP_ROOTFS) + "\n" +
                "② 基础工具（apt）       " + mark(HarnessController.STEP_TOOLS) + "\n" +
                "③ Node.js               " + mark(HarnessController.STEP_NODE) + "\n" +
                "④ deepseek-harness      " + mark(HarnessController.STEP_HARNESS)
    }

    private fun mark(step: Int): String {
        val cc = c ?: return "⬜ 未安装"
        if (cc.busy && cc.currentStep == step) return "⏳ 进行中"
        return if (cc.isStepDone(step)) "✅ 已就绪" else "⬜ 未安装"
    }

    private fun stepLabel(step: Int): String {
        val cc = c ?: return HarnessController.stepName(step)
        val name = HarnessController.stepName(step)
        return if (cc.isStepDone(step)) "重装 $name" else "安装 $name"
    }

    private fun refreshStatus() {
        val cc = c ?: return
        var done = 0
        for (s in HarnessController.STEP_ROOTFS..HarnessController.STEP_HARNESS) {
            if (cc.isStepDone(s)) done++
        }
        if (done == 4) {
            statusText.text = "✅ 全部安装完成\n\n可到「启动」页启动 Web UI。"
            installBtn.text = "重新安装（补装缺失步骤）"
        } else if (done > 0) {
            statusText.text = "🔄 已完成 $done/4 步，可一键补装剩余步骤。"
            installBtn.text = "一键安装剩余步骤"
        } else {
            statusText.text = "📦 尚未安装\n\n点击下方按钮：\n一键安装 = 按顺序补装 4 个步骤\n也可单独安装某一步\n（约需 5~15 分钟，请保持网络畅通）"
            installBtn.text = "一键安装"
        }
    }
}
