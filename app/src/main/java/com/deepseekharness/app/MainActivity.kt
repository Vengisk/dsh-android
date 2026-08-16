package com.deepseekharness.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    /** 当前前台 Activity（HttpShellService 用它弹确认框）；null = 不在前台 */
    companion object {
        @Volatile
        var current: MainActivity? = null

        // 备份提醒频率分级：默认每 6 次 → 勾选"少提醒我"依次升级为 15 / 30 / 100 次
        private val REMIND_INTERVALS = intArrayOf(6, 15, 30, 100)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashCatcher.install(this)

        // 首次启动进入引导页
        val prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE)
        if (!prefs.getBoolean("welcomed", false)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // 沉浸式全屏（隐藏状态栏 + 系统导航栏）
        hideSystemUI()

        requestPermissions()
        requestBatteryOptimization()
        maybeShowBackupReminder()
        maybeCheckUpdate()

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        if (savedInstanceState == null) {
            selectTab(R.id.nav_install)
        }

        nav.setOnItemSelectedListener { item ->
            selectTab(item.itemId)
            true
        }
    }

    /**
     * 切换底部 Tab：复用已有 Fragment 实例（按 tag 查找），
     * 用 show/hide 而非 replace，保留各页状态（滚动位置、WebView、终端会话等）。
     */
    private fun selectTab(menuId: Int) {
        val tag: String = when (menuId) {
            R.id.nav_launch -> "frag_launch"
            R.id.nav_config -> "frag_config"
            R.id.nav_workspace -> "frag_workspace"
            R.id.nav_terminal -> "frag_terminal"
            else -> "frag_install"
        }
        val fm = supportFragmentManager
        var target: Fragment? = fm.findFragmentByTag(tag)
        if (target == null) {
            target = when (menuId) {
                R.id.nav_launch -> LaunchFragment()
                R.id.nav_config -> ConfigFragment()
                R.id.nav_workspace -> WorkspaceFragment()
                R.id.nav_terminal -> TerminalFragment()
                else -> InstallFragment()
            }
        }
        val tx: FragmentTransaction = fm.beginTransaction()
        for (f in fm.fragments) {
            if (f !== target && !f.isHidden) tx.hide(f)
        }
        if (target.isAdded) {
            tx.show(target)
        } else {
            tx.add(R.id.fragment_container, target, tag)
        }
        tx.commit()
    }

    /** 显示/隐藏底部导航栏（WebView 全屏时隐藏） */
    fun setBottomNavVisible(visible: Boolean) {
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        if (nav != null) nav.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 自动申请所需权限：通知（前台服务需要）+ 电池优化白名单（保活） */
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        current = this
        TaskNotifier.appInForeground = true
    }

    override fun onPause() {
        super.onPause()
        current = null
        TaskNotifier.appInForeground = false
    }

    private fun requestBatteryOptimization() {
        // 电池优化白名单（保活更稳，跳转系统设置让用户一键允许）
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            }
        } catch (ignored: Exception) {
        }
    }

    // ================= 检查更新 =================
    /** 后台静默检查 GitHub Releases；发现新版弹窗（取消 = 本次忽略该版本） */
    private fun maybeCheckUpdate() {
        val prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE)
        if (!prefs.getBoolean("check_update", true)) return
        val ignored = prefs.getString("ignored_version", "")
        val current: String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            return
        }
        Thread {
            val tag = UpdateChecker.checkLatestVersion()
            if (tag == null || tag == ignored) return@Thread
            if (!UpdateChecker.isNewer(tag, current)) return@Thread
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("发现新版本 $tag")
                    .setMessage("当前版本 v$current\n是否前往下载？")
                    .setPositiveButton("更新") { _, _ ->
                        AboutDialog.openBrowser(
                            this,
                            "https://github.com/Vengisk/dsh-android/releases/latest"
                        )
                    }
                    .setNegativeButton("取消") { _, _ ->
                        prefs.edit().putString("ignored_version", tag).apply()
                    }
                    .show()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    // ================= 备份提醒 =================
    private fun maybeShowBackupReminder() {
        val prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE)
        val count = prefs.getInt("launch_count", 0) + 1
        val level = prefs.getInt("reminder_level", 0)
        val last = prefs.getInt("last_reminded", 0)
        prefs.edit().putInt("launch_count", count).apply()
        val interval = REMIND_INTERVALS[Math.min(level, REMIND_INTERVALS.size - 1)]
        if (count - last < interval) return

        val box = LayoutInflater.from(this).inflate(R.layout.dialog_remind_backup, null)
        val lessCb = box.findViewById<CheckBox>(R.id.remind_less)
        val labels = arrayOf(
            "少提醒我（改为每 15 次提醒）",
            "少提醒我（改为每 30 次提醒）",
            "少提醒我（改为每 100 次提醒）"
        )
        if (level < labels.size) {
            lessCb.text = labels[level]
        } else {
            lessCb.visibility = View.GONE
        }
        AlertDialog.Builder(this)
            .setTitle("建议备份数据")
            .setMessage("已启动 $count 次，建议把配置和对话记录导出到\n" +
                "Download/dsh-android 备份，防止意外丢失。")
            .setView(box)
            .setPositiveButton("立即备份") { _, _ ->
                confirmReminder(prefs, level, lessCb, count)
                startBackup()
            }
            .setNegativeButton("取消") { _, _ ->
                confirmReminder(prefs, level, lessCb, count)
            }
            .show()
    }

    private fun confirmReminder(prefs: SharedPreferences, level: Int,
                                lessCb: CheckBox?, count: Int) {
        if (lessCb != null && lessCb.isChecked) {
            prefs.edit().putInt("reminder_level", level + 1).apply()
        }
        prefs.edit().putInt("last_reminded", count).apply()
    }

    /** 后台执行全量备份，完成后弹窗告知目录并可复制路径 */
    private fun startBackup() {
        Toast.makeText(this, "正在备份，请稍候…", Toast.LENGTH_SHORT).show()
        Thread {
            val path = BackupManager.backupToExternal(this, HarnessController.get(this))
            runOnUiThread {
                if (path == null) {
                    Toast.makeText(this, "备份失败：环境可能未安装或空间不足", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("备份完成")
                    .setMessage("已导出到：\n$path")
                    .setPositiveButton("复制路径") { _, _ ->
                        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("backup", path))
                            Toast.makeText(this, "路径已复制", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("好", null)
                    .show()
            }
        }.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }
}
