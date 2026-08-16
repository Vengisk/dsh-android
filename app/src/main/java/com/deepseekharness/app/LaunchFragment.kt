package com.deepseekharness.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.fragment.app.Fragment
import java.net.HttpURLConnection
import java.net.URL

/** 启动模块：启动/重启/停止 Web UI；启动后自动检测就绪并弹出预览 */
class LaunchFragment : Fragment() {

    private var c: HarnessController? = null
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var restartBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var controls: LinearLayout
    private lateinit var exitBtn: ImageButton
    private lateinit var placeholder: ImageView
    private var fullscreen = false
    private var polling = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitFullscreen()
        }
    }

    private val stateListener = HarnessController.StateListener { refreshFromState() }

    @Nullable
    override fun onCreateView(@NonNull inflater: LayoutInflater, @Nullable container: ViewGroup?,
                              @Nullable savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_launch, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        c = HarnessController.get(requireContext())
        webView = view.findViewById(R.id.webview)
        statusText = view.findViewById(R.id.launch_status)
        startBtn = view.findViewById(R.id.launch_start)
        restartBtn = view.findViewById(R.id.launch_open)
        stopBtn = view.findViewById(R.id.launch_stop)
        controls = view.findViewById(R.id.launch_controls)
        exitBtn = view.findViewById(R.id.launch_exit)
        placeholder = view.findViewById(R.id.webview_placeholder)

        val ws: WebSettings = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                placeholder.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }
        }

        c?.addStateListener(stateListener)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        exitBtn.setOnClickListener { exitFullscreen() }

        startBtn.setOnClickListener {
            if (c?.isHarnessInstalled() != true) {
                Toast.makeText(requireContext(), "请先在「安装」模块完成安装", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // 通过前台服务启动：强保活 + 后台运行（切走不杀）
            val i = Intent(requireContext(), HarnessService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(i)
            } else {
                requireContext().startService(i)
            }
            statusText.text = "正在启动 Web UI，检测到就绪后自动打开预览…"
            pollWebReady()
        }

        restartBtn.setOnClickListener {
            if (c?.isHarnessInstalled() != true) {
                Toast.makeText(requireContext(), "请先完成安装", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            exitFullscreen()
            statusText.text = "正在重启 Web UI…"
            val stop = Intent(requireContext(), HarnessService::class.java)
                .setAction(HarnessService.ACTION_STOP)
            requireContext().startService(stop)
            // 稍等进程退出，再重新拉起
            mainHandler.postDelayed({
                val i = Intent(requireContext(), HarnessService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(i)
                } else {
                    requireContext().startService(i)
                }
                statusText.text = "正在重启 Web UI，检测到就绪后自动打开预览…"
                pollWebReady()
            }, 1500)
        }

        stopBtn.setOnClickListener {
            val i = Intent(requireContext(), HarnessService::class.java)
                .setAction(HarnessService.ACTION_STOP)
            requireContext().startService(i)
            exitFullscreen()
            statusText.text = "已发送停止命令"
        }

        // 切模块回来：如果 Web 还在跑，自动恢复全屏预览
        if (c?.isWebRunning() == true) {
            webView.post { openPreview() }
        } else {
            statusText.text = "提示：先到「安装」页完成安装，再回到这里启动。"
        }
    }

    /** 轮询检测 WebUI 就绪（HTTP 200），就绪后自动打开预览 */
    private fun pollWebReady() {
        if (polling) return
        polling = true
        val url = "http://127.0.0.1:${c?.getPort()}/"
        Thread {
            var ok = false
            for (i in 0 until 60) { // 最多约 2 分钟
                if (c?.isWebRunning() != true && !ok) {
                    // 服务进程都没了就别等了（除非刚重启还在拉起中，给它几轮机会）
                    if (i > 20) break
                }
                if (httpOk(url)) {
                    ok = true
                    break
                }
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    break
                }
            }
            polling = false
            if (ok && isAdded) {
                mainHandler.post { openPreview() }
            } else if (isAdded) {
                mainHandler.post {
                    statusText.text = "等待 Web UI 就绪超时，可点「重启」再试，或检查日志"
                }
            }
        }.start()
    }

    private fun httpOk(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val code = conn.responseCode
            conn.disconnect()
            code in 200 until 500
        } catch (e: Exception) {
            false
        }
    }

    private fun openPreview() {
        val url = "http://127.0.0.1:${c?.getPort()}/"
        webView.loadUrl(url)
        enterFullscreen()
    }

    private fun enterFullscreen() {
        fullscreen = true
        backCallback.isEnabled = true
        controls.visibility = View.GONE
        statusText.visibility = View.GONE
        exitBtn.visibility = View.VISIBLE
        (activity as? MainActivity)?.setBottomNavVisible(false)
        val decor = activity?.window?.decorView
        if (decor != null) {
            decor.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun exitFullscreen() {
        fullscreen = false
        backCallback.isEnabled = false
        controls.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        exitBtn.visibility = View.GONE
        (activity as? MainActivity)?.setBottomNavVisible(true)
        val decor = activity?.window?.decorView
        if (decor != null) {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        c?.removeStateListener(stateListener)
        // 退出 Fragment 时恢复底部导航
        (activity as? MainActivity)?.setBottomNavVisible(true)
    }

    private fun refreshFromState() {
        if (!isAdded) return
        val cc = c ?: return
        if (!cc.error.isNullOrEmpty()) {
            statusText.text = cc.error
        } else if (!cc.message.isNullOrEmpty()) {
            statusText.text = cc.message
        } else if (cc.busy) {
            statusText.text = cc.stage
        }
    }
}
