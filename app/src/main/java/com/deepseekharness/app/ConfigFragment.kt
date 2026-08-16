package com.deepseekharness.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.fragment.app.Fragment

/** 配置模块：API key / 端口 / 模型 / 沙箱模式 */
class ConfigFragment : Fragment() {

    private var c: HarnessController? = null
    private var apiKeyEdit: EditText? = null
    private var portEdit: EditText? = null
    private var modelEdit: EditText? = null
    private var modeSpinner: Spinner? = null
    private var confirmShellCb: CheckBox? = null
    private var checkUpdateCb: CheckBox? = null

    @Nullable
    override fun onCreateView(@NonNull inflater: LayoutInflater, @Nullable container: ViewGroup?,
                              @Nullable savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }

    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        c = HarnessController.get(requireContext())
        apiKeyEdit = view.findViewById(R.id.config_api_key)
        portEdit = view.findViewById(R.id.config_port)
        modelEdit = view.findViewById(R.id.config_model)
        modeSpinner = view.findViewById(R.id.config_mode)
        confirmShellCb = view.findViewById(R.id.config_confirm_shell)
        checkUpdateCb = view.findViewById(R.id.config_check_update)
        val saveBtn: Button = view.findViewById(R.id.config_save)
        val repoLink: TextView = view.findViewById(R.id.config_repo_link)

        val modes = arrayOf("danger-full-access", "workspace-write", "read-only")
        val adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, modes)
        modeSpinner?.adapter = adapter

        loadConfig()

        saveBtn.setOnClickListener {
            val apiKey = apiKeyEdit?.text.toString().trim()
            val portStr = portEdit?.text.toString().trim()
            val model = modelEdit?.text.toString().trim()
            // 输入校验：API key 必填
            if (apiKey.isEmpty()) {
                apiKeyEdit?.error = "请填写 DeepSeek API Key"
                apiKeyEdit?.requestFocus()
                return@setOnClickListener
            }
            // 端口校验：必须是 1~65535 的整数
            if (portStr.isEmpty()) {
                portEdit?.error = "请填写端口"
                portEdit?.requestFocus()
                return@setOnClickListener
            }
            try {
                val port = portStr.toInt()
                if (port < 1 || port > 65535) {
                    portEdit?.error = "端口需在 1~65535 之间"
                    portEdit?.requestFocus()
                    return@setOnClickListener
                }
            } catch (e: NumberFormatException) {
                portEdit?.error = "端口必须是数字"
                portEdit?.requestFocus()
                return@setOnClickListener
            }
            c?.setApiKey(apiKey)
            c?.setPort(portStr)
            c?.setModel(model)
            c?.setPermissionMode(modeSpinner?.selectedItem as String)
            requireContext().getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .edit().putBoolean("confirm_shell", confirmShellCb?.isChecked == true)
                .putBoolean("check_update", checkUpdateCb?.isChecked == true).apply()
            Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show()
        }

        // 关于入口：点版本号弹「关于」对话框（GitHub / QQ 群）
        // 版本号动态显示（与应用信息一致）
        try {
            val v = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
            repoLink.text = "DeepSeek Harness v$v"
        } catch (ignored: Exception) {
        }
        repoLink.setOnClickListener { AboutDialog.show(requireContext()) }
    }

    private fun loadConfig() {
        apiKeyEdit?.setText(c?.getApiKey())
        portEdit?.setText(c?.getPort())
        modelEdit?.setText(c?.getModel())
        val mode = c?.getPermissionMode()
        var idx = 0
        if ("workspace-write" == mode) idx = 1
        else if ("read-only" == mode) idx = 2
        modeSpinner?.setSelection(idx)
        confirmShellCb?.isChecked = requireContext()
            .getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
            .getBoolean("confirm_shell", true)
        checkUpdateCb?.isChecked = requireContext()
            .getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
            .getBoolean("check_update", true)
    }
}
